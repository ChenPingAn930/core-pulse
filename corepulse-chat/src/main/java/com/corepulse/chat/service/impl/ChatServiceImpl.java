package com.corepulse.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.corepulse.chat.Enum.FeatureGuideEnum;
import com.corepulse.chat.Enum.PromptEnum;
import com.corepulse.chat.Enum.ReinstallGuideEnum;
import com.corepulse.chat.config.RecordingToolCallingManager;
import com.corepulse.chat.event.ChatEventPublisher;
import com.corepulse.chat.mapper.ChatSessionMapper;
import com.corepulse.chat.model.dto.ChatSendRequest;
import com.corepulse.chat.model.vo.ChatSendVO;
import com.corepulse.chat.model.vo.PendingCommandVO;
import com.corepulse.chat.rag.KnowledgeService;
import com.corepulse.chat.service.ChatService;
import com.corepulse.chat.service.CommandPendingService;
import com.corepulse.chat.service.SessionService;
import com.corepulse.common.event.CommandConfirmNotifier;
import com.corepulse.domain.entity.ChatMessage;
import com.corepulse.domain.entity.ChatSession;
import com.corepulse.domain.enums.CommandStatus;
import com.corepulse.domain.enums.MessageRole;
import com.corepulse.domain.mapper.ChatMessageMapper;
import com.corepulse.system.tool.UninstallVerifyToolkit;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AI 对话编排服务实现 - 基于 Spring AI ChatClient
 * <p>
 * 用户消息 -> 构建上下文(系统提示+历史) -> LLM -> 保存并返回回复
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final int MAX_HISTORY = 80;
    /** 历史中最多保留的工具执行记录条数：工具结果往往只对当轮有用，保留太多会撑爆上下文 */
    private static final int MAX_TOOL_RECORDS = 8;

    /** 工具结果入库时的最大长度（避免大输出撑爆历史上下文，完整结果当轮已使用过） */
    private static final int MAX_TOOL_RESULT_LENGTH = 1200;

    @Qualifier("primaryChatClient")
    private final ChatClient primaryChatClient;

    @Qualifier("backupChatClient")
    private final ChatClient backupChatClient;

    private final SessionService sessionService;
    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final KnowledgeService knowledgeService;
    private final ChatEventPublisher chatEventPublisher;
    private final UninstallVerifyToolkit uninstallVerifyToolkit;
    private final ObjectMapper objectMapper;

    /** 待确认命令门面：用于校验"模型说需要确认"到底有没有真实的待确认记录 */
    private final CommandPendingService commandPendingService;

    /** 卡片事件推送：新消息时补推历史待确认卡片（common 域接口，实现由 web 模块提供） */
    private final CommandConfirmNotifier commandConfirmNotifier;

    /**
     * 模型"要求用户去点确认卡片"的话术特征
     * <p>
     * 不能只匹配工具原文那一句：模型会改写成"需要你在界面确认卡片上点击确认"，
     * 字面完全不同、语义一模一样，用户照样会去找一张不存在的卡片。
     * 这里只收"指向本系统确认卡片"的措辞，不收泛化的"点击确认"——
     * 后者在讲解手动卸载向导时也会出现，误判把正常回答改掉。
     */
    private static final List<String> CONFIRM_CLAIM_MARKERS = List.of(
            "确认卡片", "需要用户确认", "待确认记录", "在界面上确认",
            "确认后才会执行", "确认后才会真正执行");

    /**
     * 发送消息 - AI 对话核心流程
     * <p>
     * 步骤：获取/创建会话 -> 自动生成标题 -> 保存用户消息 -> 构建上下文buildMessages() -> 调用 LLM -> 保存回复
     *
     * @param request 发送消息请求（包含会话ID和用户消息内容）
     * @return AI 回复结果（会话ID + 回复内容 + 异步任务ID）
     */
    @Override
    @Transactional
    public ChatSendVO send(ChatSendRequest request) {
        Long sessionId = request.getSessionId();
        String content = request.getContent();

        // 1. 获取/创建会话
        ChatSession session = sessionService.getOrCreate(sessionId);

        // 2. 首条消息自动生成会话标题
        if ("新会话".equals(session.getTitle())) {
            String newTitle = content.length() > 20 ? content.substring(0, 20) : content;
            session.setTitle(newTitle);
            sessionService.update(session);
        }

        // 3. 保存用户消息（source 标记用于让前端区分"用户亲手输入"和"系统代发的确认消息"）
        saveMessage(session.getId(), MessageRole.USER.getValue(), content, null, null,
                buildSourceExtra(request.getSource()));

        // 3.5 补推历史待确认卡片：卡片只靠一次性事件渲染，推送失败或前端没连上时就会永久丢失，
        // 用户发下一条消息时顺手补一次。前端应按 messageId 去重，避免同一条命令出现两张卡片
        repushPendingCards(session.getId());

        // 4. 推送"思考中"事件：让前端在 LLM 处理期间实时看到当前阶段，而不是干等 HTTP 响应
        chatEventPublisher.publishThinking(session.getId(), "思考中");

        // 5. 构建上下文消息（传入用户消息用于重装意图检测）
        List<Message> messages = buildMessages(session.getId(), content);

        // 6. 调用 LLM（注入工具上下文：sessionId 供工具方法使用，不暴露给 LLM 参数）
        // 同时把会话 ID 写入 RecordingToolCallingManager 的 ThreadLocal，供工具执行期间推送过程事件
        // 注意：这里的 reply 是最终回复，而不是 LLM 的输出，
        String reply = null;
        List<String> invokedTools = new ArrayList<>();
        RecordingToolCallingManager.setCurrentSession(session.getId());
        boolean success = false;
        // 本轮是否真的产生过"需要用户确认"的工具返回（由 saveToolRecords 在 finally 里回填）
        boolean confirmationRequested = false;
        try {
            // 主模型重试：用 Spring Retry 的 RetryTemplate 替代手写 for 循环 + Thread.sleep
            // 为什么用 RetryTemplate 而不是手写循环？
            //   1. 退避策略（指数退避）由框架管理，代码更简洁、更规范
            //   2. RetryTemplate 内部用 Thread.sleep 实现等待，但封装了中断处理等细节
            //   3. 语义清晰：RetryCallback 里"抛异常"就重试，"正常返回"就结束
            // 退避策略：首次等 5 秒，每次翻倍，上限 9 秒，最多重试 5 次
            // 为什么用指数退避而不是固定等待？
            //   1. 服务刚超时/报错时往往处于"过载"状态，立即重试大概率还是失败，反而加重负担
            //   2. 指数退避让等待时间随失败次数递增，给服务更多恢复时间，成功率更高
            RetryTemplate retryTemplate = RetryTemplate.builder()
                    .maxAttempts(3)                    // 最多尝试 3 次（含首次）
                    .exponentialBackoff(3000, 3, 9000) // 首次等 5 秒，每次翻倍，上限 9 秒
                    .build();
            try {
                // RetryCallback.doWithRetry 里写"一次尝试"的逻辑：
                //   - 正常返回（拿到有效回复）→ 重试结束
                //   - 抛异常 → 触发重试（按退避策略等待后重试）
                reply = retryTemplate.execute(context -> {
                    // context.getRetryCount() 返回当前是第几次重试（0 开始），用于日志
                    log.info("会话 {} 调用模型, 第 {} 次尝试", session.getId(), context.getRetryCount() + 1);
                    // 调用 LLM
                    ChatResponse chatResponse = callModel(primaryChatClient, messages, session.getId(), invokedTools);
                    // 记录本次回复所基于的工具调用（工具调用信息在 AssistantMessage 的 toolCalls 中）
                    chatResponse.getResults().forEach(result -> {
                        AssistantMessage output = result.getOutput();
                        // 判断输出是否为空，避免空指针异常 以及 判断 toolCalls 是否为空，避免空指针异常
                        // 注意output.getToolCalls()不是指代的工具调用，而是指代的这里的对象是否被创建
                        if (output != null && output.getToolCalls() != null) {
                            // 获取工具调用名称
                            output.getToolCalls().forEach(tc -> invokedTools.add(tc.name()));
                        }
                    });
                    // 获取最终回复
                    String text = chatResponse.getResult() != null ? chatResponse.getResult().getOutput().getText() : null;
                    // 关键：如果 LLM 没返回有效文本，抛异常触发重试；
                    // 否则 RetryTemplate 认为"成功"，直接返回，不会重试
                    if (text == null || text.isBlank()) {
                        throw new IllegalStateException("LLM 返回空回复");
                    }
                    return text;
                });
                log.info("会话 {} 获取到回复: {}", session.getId(), reply);
                success = true;
            } catch (Exception e) {
                // 3 次都失败（或返回空回复）会走到这里，记录日志后走降级逻辑
                log.warn("主模型调用 3 次均失败, 降级到备用模型: {}", e.getMessage());
            }
            if (!success) {
                try {
                    // 添加降级处理更换其他模型
                    ChatResponse chatResponse = callModel(backupChatClient, messages, session.getId(), invokedTools);
                    if (chatResponse != null && chatResponse.getResult() != null ){
                        log.info("会话 {} 获取到回复: {},来自备用模型", session.getId(), chatResponse);
                        reply = chatResponse.getResult().getOutput().getText();
                        success = true;
                    }
                }
                catch (Exception e) {
                    log.error("LLM 调用失败: sessionId={}", session.getId(), e);
                }
            }
            if (!success) {
                reply = "抱歉，AI 服务暂时不可用，请稍后重试。";
            }
        }catch (Exception e) {
            log.error("LLM 调用失败: sessionId={}", session.getId(), e);
            reply = "抱歉，AI 服务暂时不可用，请稍后重试。";
        } finally {
            // 取出本轮内部循环中执行过的工具调用结果并存入历史（role=tool），
            // 使跨轮次时 LLM 能看到上一轮工具的真实输出（如 taskId、扫描数据）；
            // 无论成功失败都需 drain，防止线程复用时串数据。
            // 返回值表示本轮是否真有命令走到"待用户确认"，用于识别模型的口头确认
            confirmationRequested = saveToolRecords(session.getId());
            RecordingToolCallingManager.clearCurrentSession();
        }
        // 记录最终回复这一轮是否还有工具调用
        // 说明：多轮工具调用发生在 chatClient 内部循环中，最终 chatResponse 通常是 LLM 直接输出文字的那一轮
        if (!invokedTools.isEmpty()) {
            log.info("会话 {} 最终回复轮次仍触发了工具调用: tools={}", session.getId(), invokedTools);
        } else {
            log.info("会话 {} 最终回复由 LLM 直接生成文字（工具调用可能发生在更早轮次，见各工具日志）", session.getId());
        }

        if (reply == null || reply.isBlank()) {
            reply = "我已经处理了你的请求，但没能生成合适的回复，请再试一次。";
        }

        // 6.5 卸载残留校验仲裁：模型声称"卸载干净"但本轮没调用 verifyUninstallApp → 强制补验
        // 原理：残留目录名往往和软件名无关，模型按软件名 dir 搜不到就"背稿"说干净。
        // 后端只认"verifyUninstallApp 真被调用过 + 其返回 JSON 的 clean"，防止假验收。
        boolean isUninstallIntent = hasUninstallIntent(request.getContent());
        boolean claimsClean = isUninstallIntent && CLAIM_CLEAN_WORDS.stream().anyMatch(reply::contains);
        boolean verified = invokedTools.stream().anyMatch(t -> t.startsWith("verifyUninstallApp"));
        if (isUninstallIntent && claimsClean && !verified) {
            log.warn("[卸载校验拦截] 会话 {} 声称卸载干净但未调用 verifyUninstallApp，已代跑", session.getId());
            String verifyJson = tryRunVerify(request.getContent(), session.getId());
            reply = "本轮缺少卸载后校验，已由系统代为执行 verifyUninstallApp，结果如下：\n" + verifyJson
                    + "\n（请确认并告知用户：clean 为 false 时残留清单见上，是否清理请征得用户同意。）";
        }

        // 6.6 确认卡片存在性仲裁：模型回复里说"需要用户确认"，但库里没有有效待确认记录 → 它在背稿
        // 原理：PromptEnum 里教过它确认文案，模型完全可以不调工具、直接复述这句话，
        // 用户照着提示去点卡片却发现根本没有卡片。这里只做"是否存在"的校验，
        // 绝不从回复文本里反推命令自动补建记录 —— 那会造成"用户确认 A、实际执行 B"的新漏洞。
        String confirmClaim = CONFIRM_CLAIM_MARKERS.stream()
                .filter(reply::contains)
                .findFirst()
                .orElse(null);
        if (confirmClaim != null) {
            // 两个信号缺一不可地"或"起来，是因为它们各自会漏：
            // 1. confirmationRequested —— 本轮工具真实返回过"需要用户确认"，内存信号，不受事务可见性影响；
            // 2. hasValidPending —— 库里确实存在有效记录，能覆盖"引用上一轮旧卡片"的场景。
            // 为什么要两个都看：send() 在事务里（REPEATABLE READ），快照在 buildMessages 首次查询时就固定了；
            // 而待确认记录是本轮用 REQUIRES_NEW 另开事务插进去的，对本事务的快照不可见，
            // 只查库会得出"没有卡片"的错误结论，把明明已经弹出卡片的一轮改成"提交失败"
            boolean cardExists = confirmationRequested || commandPendingService.hasValidPending(session.getId());
            if (!cardExists) {
                log.warn("[确认卡片拦截] 会话 {} 回复声称需要确认(命中措辞:{}), 但本轮无确认返回且库中无有效记录",
                        session.getId(), confirmClaim);
                reply = "这条命令没有真正提交到系统，因此界面上不会出现确认卡片，我也不会执行它。"
                        + "请重新发送一次指令，我会重新提交执行申请，再由你在卡片上确认。";
            }
        }

        // 7. 保存 AI 回复
        saveMessage(session.getId(), MessageRole.ASSISTANT.getValue(), reply, null, null);
        log.info("会话 {} 回复完成, replyLen={}", session.getId(), reply.length());

        // 8. 摘要只处理上次游标之后的消息；摘要失败时不推进游标
        //    【修复 2026-08-25】改用 session.getId()（始终非空，line 79 已创建/获取会话）代替
        //    原始请求 sessionId。原代码在请求不带 sessionId（首条消息/本地会话）时
        //    selectById(null) 返回 null，.getLastSummarizedMessageId() 触发 NPE，接口返回 500，前端走兜底提示。
        ChatSession currentSession = sessionMapper.selectById(session.getId());
        Long lastSummarizedMessageId = (currentSession == null || currentSession.getLastSummarizedMessageId() == null)
        ? 0L : currentSession.getLastSummarizedMessageId();
        // 获取未摘要的消息
        List<ChatMessage> unsummarizedMessages = messageMapper.selectList(
        new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, session.getId())
                .gt(ChatMessage::getId, lastSummarizedMessageId)
                .orderByAsc(ChatMessage::getId)
        );

        log.info("会话 {} 摘要游标={}, 未摘要消息数={}",
                session.getId(), lastSummarizedMessageId, unsummarizedMessages.size());
        if (unsummarizedMessages.size() < 80) {
            log.info("会话 {} 未摘要消息不足80条，不生成摘要", session.getId());
            return ChatSendVO.builder()
                    .sessionId(session.getId())
                    .reply(reply)
                    .taskId(null)
                    .build();
        }
        // 获取旧摘要
        String summary = currentSession.getSummary();
        if (summary == null || summary.isBlank()) {
            summary = "无";
        }
        // 构建摘要消息
        List<Message> summaryMessages = new ArrayList<>();
        summaryMessages.add(new UserMessage("旧摘要：\n" + summary + "\n\n新增对话："));
        for (ChatMessage message : unsummarizedMessages) {
            if (MessageRole.USER.getValue().equals(message.getRole())) {
                summaryMessages.add(new UserMessage(message.getContent()));
            } else if (MessageRole.ASSISTANT.getValue().equals(message.getRole())) {
                summaryMessages.add(new AssistantMessage(message.getContent()));
            } else if (MessageRole.TOOL.getValue().equals(message.getRole())) {
                summaryMessages.add(new UserMessage("[工具执行记录] 工具 " + message.getToolName()
                        + " 的执行结果：" + message.getContent()));
            }
        }

        try {
            // 调用摘要生成模型，主模型失败时降级到备用模型
            ChatResponse summaryResponse = null;
            try {
                summaryResponse = primaryChatClient.prompt()
                        .messages(summaryMessages)
                        .system(PromptEnum.SUMMARY_GENERATION.getContent())
                        .call()
                        .chatResponse();
            } catch (Exception e) {
                log.warn("会话 {} 主模型摘要生成失败，降级到备用模型: {}", session.getId(), e.getMessage());
                summaryResponse = backupChatClient.prompt()
                        .messages(summaryMessages)
                        .system(PromptEnum.SUMMARY_GENERATION.getContent())
                        .call()
                        .chatResponse();
            }
            String newSummary = summaryResponse.getResult() == null
                    || summaryResponse.getResult().getOutput() == null
                    ? null : summaryResponse.getResult().getOutput().getText();
            if (newSummary == null || newSummary.isBlank()) {
                log.warn("会话 {} 摘要内容为空，保留原摘要和游标", session.getId());
            } else {
                Long summaryToMessageId = unsummarizedMessages.get(unsummarizedMessages.size() - 1).getId();
                LambdaUpdateWrapper<ChatSession> summaryUpdate = new LambdaUpdateWrapper<ChatSession>()
                        .set(ChatSession::getSummary, newSummary.trim())
                        .set(ChatSession::getLastSummarizedMessageId, summaryToMessageId)
                        .eq(ChatSession::getId, sessionId);
                if (currentSession.getLastSummarizedMessageId() == null) {
                    summaryUpdate.isNull(ChatSession::getLastSummarizedMessageId);
                } else {
                    summaryUpdate.eq(ChatSession::getLastSummarizedMessageId,
                            currentSession.getLastSummarizedMessageId());
                }
                int updated = sessionMapper.update(summaryUpdate);
                if (updated > 0) {
                    log.info("会话 {} 更新摘要和游标成功, toMessageId={}",
                            session.getId(), summaryToMessageId);
                } else {
                    log.warn("会话 {} 摘要状态未更新，可能存在并发更新", session.getId());
                }
            }
        } catch (Exception e) {
            log.warn("会话 {} 摘要生成或更新失败，保留原摘要和游标", session.getId(), e);
        }

        return ChatSendVO.builder()
                .sessionId(session.getId())
                .reply(reply)
                .taskId(null)
                .build();
    }

    /** 重装系统意图关键词（命中则注入重装引导） */
    private static final List<String> REINSTALL_KEYWORDS = List.of(
            "重装系统", "装系统", "重装", "系统盘", "安装系统", "制作u盘",
            "制作U盘", "启动盘", "安装u盘", "重做系统", "重灌系统"
    );

    /** 卸载意图关键词（命中则触发卸载残留校验仲裁） */
    private static final List<String> UNINSTALL_KEYWORDS = List.of(
            "卸载", "删除软件", "删掉", "remove", "uninstall", "卸掉"
    );

    /** 声称"卸载干净"的措辞（命中且未调用 verify 工具则拦截补验） */
    private static final List<String> CLAIM_CLEAN_WORDS = List.of(
            "卸载", "删除", "已卸载", "干净", "无残留"
    );

    /** 磁盘清理意图关键词（命中则注入磁盘清理专项引导） */
    private static final List<String> DISK_CLEAN_KEYWORDS = List.of(
            "清理", "磁盘满", "磁盘空间", "c盘", "C盘", "空间不足", "清理垃圾", "清垃圾"
    );

    /** 运行库修复意图关键词（命中则注入运行库专项引导） */
    private static final List<String> VCREDIST_KEYWORDS = List.of(
            "运行库", "vc_redist", "vcredist", "msvcp", "vcruntime", "缺少dll", "缺少DLL", "dll缺失"
    );

    /** 压力测试意图关键词（命中则注入压测专项引导） */
    private static final List<String> STRESS_KEYWORDS = List.of(
            "压力测试", "压测", "烤机", "烤鸡", "满载测试", "稳定性测试", "cpu测试", "内存测试"
    );

    /**
     * 构建发送给 LLM 的消息列表
     * <p>
     * 组装顺序：系统提示词 + 该会话最近 N 条历史消息（按时间正序）+ 当前用户消息。
     * 历史中的 tool 消息（工具执行结果）会以"工具执行记录"形式还原进上下文，
     * 保证跨轮次时 LLM 能看到上一轮工具的真实输出；system 消息不纳入上下文。
     * <p>
     * 若用户消息命中「重装系统」意图，则额外注入重装系统图文引导（ReinstallGuideEnum），
     * 让 LLM 按标准流程引导用户，同时不影响日常对话。
     *
     * @param sessionId 会话 ID，用于查询该会话的历史消息
     * @param userContent 当前用户消息内容，用于意图检测
     * @return 组装好的 Spring AI Message 列表
     */
    private List<Message> buildMessages(Long sessionId, String userContent) {
        // 构建消息列表
        List<Message> messages = new ArrayList<>();

        // 系统提示
        messages.add(new SystemMessage(PromptEnum.SYSTEM_DEFAULT.getContent()));

        // RAG：检索个人知识库，命中则注入相关知识片段，辅助诊断
        // topK=5 提高硬件操作细节(如 4.1 内存拔插)被命中的概率，避免只命中概览块而丢失安全细节
        // 短确认消息（可以/好的/继续等）跳过检索，避免无关片段注入上下文干扰模型
        if (!isShortConfirmation(userContent)) {
            List<String> knowledge = knowledgeService.search(userContent, 5);
            if (!knowledge.isEmpty()) {
                // 组装知识片段为系统提示
                messages.add(new SystemMessage(buildKnowledgePrompt(knowledge)));
            }
        }

        // 重装系统意图检测：命中则注入图文引导
        if (hasReinstallIntent(userContent)) {
            messages.add(new SystemMessage(buildReinstallGuide()));
        }

        // 卸载意图检测：命中则注入卸载残留校验引导（按需注入，避免常驻 SYSTEM_DEFAULT 撑爆上下文）
        if (hasUninstallIntent(userContent)) {
            messages.add(new SystemMessage(FeatureGuideEnum.UNINSTALL.getContent()));
        }

        // 磁盘清理意图检测：命中则注入磁盘清理专项引导
        if (hasDiskCleanIntent(userContent)) {
            messages.add(new SystemMessage(FeatureGuideEnum.DISK_CLEAN.getContent()));
        }

        // 运行库修复意图检测：命中则注入运行库专项引导
        if (hasVcRedistIntent(userContent)) {
            messages.add(new SystemMessage(FeatureGuideEnum.VCREDIST.getContent()));
        }

        // 压力测试意图检测：命中则注入压测专项引导
        if (hasStressIntent(userContent)) {
            messages.add(new SystemMessage(FeatureGuideEnum.STRESS.getContent()));
        }

        /* 历史消息（最近 MAX_HISTORY 条，按 ID 正序）*/
        List<ChatMessage> history = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByDesc(ChatMessage::getId)
                        .last("LIMIT " + MAX_HISTORY));
        // 倒序，保证按时间正序
        Collections.reverse(history);
        // 工具记录瘦身：先统计历史里共有多少条工具记录，只保留最后 MAX_TOOL_RECORDS 条
        // （工具结果往往只对当轮有用，旧工具结果对当前回答帮助不大，保留太多会撑爆上下文）
        long totalToolRecords = history.stream()
                .filter(m -> MessageRole.TOOL.getValue().equals(m.getRole()))
                .count();
        long skippedToolRecords = 0;
        // 组装历史消息
        for (ChatMessage m : history) {
            // 拿到消息角色 user / assistant / system / tool */
            String role = m.getRole();
            if (MessageRole.USER.getValue().equals(role)) {
                messages.add(new UserMessage(m.getContent()));
            } else if (MessageRole.ASSISTANT.getValue().equals(role)) {
                messages.add(new AssistantMessage(m.getContent()));
            } else if (MessageRole.TOOL.getValue().equals(role)) {
                // 只保留最近 MAX_TOOL_RECORDS 条工具记录，更早的跳过
                if (skippedToolRecords < totalToolRecords - MAX_TOOL_RECORDS) {
                    skippedToolRecords++;
                    continue;
                }
                // 命令确认记录不能渲染成"执行结果"：待确认/已确认未执行的命令如果被描述成结果，
                // 模型会误以为已经执行完，直接回答"已删除/已完成"，不再调用工具
                if (CommandStatus.PENDING.getValue().equals(m.getStatus())) {
                    // 状态已被定时任务收敛成 EXPIRED 的记录走下面的分支；
                    // 这里还要覆盖"已超时但还没被扫描到"的窗口期，否则模型会催用户去点一张已经失效的卡片
                    if (m.getCreatedAt() != null && m.getCreatedAt().isBefore(commandPendingService.timeoutCutoff())) {
                        messages.add(new UserMessage("[命令已超时未执行] " + m.getContent()
                                + "（用户未在确认时限内点击确认，命令已失效，从未执行。"
                                + "如需执行请重新提交命令并生成新的确认卡片）"));
                        continue;
                    }
                    messages.add(new UserMessage("[待确认命令，尚未执行] " + m.getContent()
                            + "（等待用户在界面上点击确认，确认前不要声称已经执行）"));
                    continue;
                }
                if (CommandStatus.CONFIRMED.getValue().equals(m.getStatus())) {
                    // 必须明确"用户已经点过确认"：否则模型会继续催用户去点确认，
                    // 而此时前端卡片已经进入执行中，界面与回复互相矛盾。
                    // 也不能再让模型调用工具执行：确认后由后端直接执行，模型重复调用只会增加一轮无效 LLM
                    messages.add(new UserMessage("[用户已在界面上确认，系统即将执行] " + m.getContent()
                            + "（用户已确认，命令由系统直接执行，不需要也不要再次调用 runShellCommand）"));
                    continue;
                }
                if (CommandStatus.EXECUTING.getValue().equals(m.getStatus())) {
                    messages.add(new UserMessage("[命令正在执行，尚无结果] " + m.getContent()
                            + "（命令正在执行中，不要声称已经完成，执行结果会写入会话）"));
                    continue;
                }
                if (CommandStatus.EXECUTED.getValue().equals(m.getStatus())) {
                    // 命令原文不是执行输出，别让模型把"命令"读成"执行结果"
                    messages.add(new UserMessage("[已执行命令] " + m.getContent()
                            + "（实际输出见后续工具执行记录）"));
                    continue;
                }
                if (CommandStatus.FAILED.getValue().equals(m.getStatus())) {
                    messages.add(new UserMessage("[命令执行失败] " + m.getContent()
                            + "（该命令执行失败，输出见后续工具执行记录，请据此分析原因并给出替代方案）"));
                    continue;
                }
                if (CommandStatus.EXPIRED.getValue().equals(m.getStatus())) {
                    // 必须说清"没执行"，否则模型会把超时当成执行失败去编原因，
                    // 或者继续催用户去点一张早已失效的卡片
                    messages.add(new UserMessage("[命令已超时未执行] " + m.getContent()
                            + "（用户未在确认时限内点击确认，命令已失效，从未执行。"
                            + "如需执行请重新提交命令并生成新的确认卡片，不要催用户去点旧卡片）"));
                    continue;
                }
                // 工具结果还原为上下文：因未保存原始 toolCallId，无法还原为 API 层 tool 消息，
                // 以带前缀的普通消息形式注入，既避免消息配对校验报错，又能让 LLM 看到工具事实
                messages.add(new UserMessage("[工具执行记录] 工具 " + m.getToolName()
                        + " 的执行结果：" + m.getContent()));
            }
            // system 消息不纳入上下文
        }

        //添加摘要进消息列表
        //1.拿到摘要
        String summary = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId))
                .getSummary();
        if (summary == null || summary.isBlank()) {
            summary = "无";
        }
        messages.add(new SystemMessage(summary));
        return messages;
    }

    /**
     * 补推仍然有效的待确认卡片（事件通道兜底）
     * <p>
     * 为什么需要它：确认卡片是 WebSocket 单次事件渲染出来的，事件丢了前端就永远看不到卡片，
     * 而对话正文里还写着"请在卡片上确认"。以数据库为准补推一次，是成本最低的自愈方式。
     * <p>
     * 注意：本方法在 send() 的事务内执行，只能读、不能改状态。子事务（savePending）也是插同一张表，
     * 父事务一旦持有范围 UPDATE 的间隙锁，子事务的插入就会一直等父事务提交，最终锁等待超时。
     *
     * @param sessionId 会话 ID
     */
    private void repushPendingCards(Long sessionId) {
        try {
            List<PendingCommandVO> pending = commandPendingService.listValidPending(sessionId);
            for (PendingCommandVO item : pending) {
                commandConfirmNotifier.publishCommandConfirm(
                        sessionId, item.getMessageId(), item.getCommand());
            }
        } catch (Exception e) {
            log.warn("补推待确认卡片失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * 保存本轮对话中执行过的工具调用结果（role=tool）
     * <p>
     * 工具结果由 RecordingToolCallingManager 在 ChatClient 内部工具循环中记录，
     * 这里取出后截断入库，保证历史上下文不被大输出撑爆。
     *
     * @param sessionId 所属会话 ID
     * @return true 表示本轮确实有一条命令走到了"待用户确认"（即界面上应该出现卡片）
     */
    private boolean saveToolRecords(Long sessionId) {
        boolean confirmationRequested = false;
        for (RecordingToolCallingManager.ToolCallRecord record : RecordingToolCallingManager.drainRecords()) {
            String result = unwrapToolResult(record.result());
            // 空结果不入库（如 del 这类无输出命令），否则界面会出现空白工具块
            if (result.isBlank()) {
                continue;
            }
            // 命令确认提示是给模型当轮看的内部文本，前端已用确认卡片表达，
            // 入库只会在上下文和界面里重复一遍。
            // 这条分支同时是"确认卡片真的产生了"的唯一可信信号：它只可能来自工具的返回值，
            // 模型自己写的文字不会进 ToolCallRecord，所以能用来识别口头确认。
            if (result.startsWith("【需要用户确认")) {
                confirmationRequested = true;
                continue;
            }
            if (result.length() > MAX_TOOL_RESULT_LENGTH) {
                result = result.substring(0, MAX_TOOL_RESULT_LENGTH) + "...(已截断)";
            }
            saveMessage(sessionId, MessageRole.TOOL.getValue(), result, record.toolName(), null);
        }
        return confirmationRequested;
    }

    /**
     * 还原工具返回的字符串
     * <p>
     * Spring AI 记录 ToolResponse.responseData 时会把 String 返回值做一层 JSON 序列化，
     * 数据库里实际是 "\"驱动器 C ...\"" 这种带首尾引号和转义的形式。
     * 这里解包成原始文本，既让过滤判断（空结果/确认提示）能命中，也让上下文和界面更干净。
     */
    private String unwrapToolResult(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            try {
                return objectMapper.readValue(raw, String.class);
            } catch (Exception e) {
                return raw.substring(1, raw.length() - 1);
            }
        }
        return raw;
    }

    /**
     * 保存一条聊天消息到数据库
     *
     * @param sessionId 所属会话 ID
     * @param role      消息角色（user / assistant / system / tool）
     * @param content   消息内容
     * @param toolName  触发的工具名称（非工具消息传 null）
     * @param taskId    关联的异步任务 ID（无则传 null）
     * @return 消息 ID
     */
    private Long saveMessage(Long sessionId, String role, String content, String toolName, Long taskId) {
        return saveMessage(sessionId, role, content, toolName, taskId, null);
    }

    /**
     * 保存一条聊天消息（可带附加标记）
     *
     * @param extra 附加信息的 JSON 字符串（写入 extra 列），无则 null
     */
    private Long saveMessage(Long sessionId, String role, String content, String toolName,
                             Long taskId, String extra) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setToolName(toolName);
        msg.setTaskId(taskId);
        msg.setExtra(extra);
        int insert = messageMapper.insert(msg);
        if (insert > 0)
            log.info("会话 {} 保存消息成功, role={}, content={}, toolName={}, taskId={}",
                    sessionId, role, content, toolName, taskId);
        else
            log.warn("会话 {} 保存消息失败，未插入任何记录", sessionId);
        return msg.getId();
    }

    /**
     * 把消息来源标记转成 extra JSON
     * <p>source 只由后端内部传常量（如 command-confirm），不接受前端输入，无需转义。
     */
    private String buildSourceExtra(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        return "{\"source\":\"" + source + "\"}";
    }

    /** 短确认/闲聊消息特征词（命中且消息很短时跳过 RAG 检索） */
    private static final List<String> SHORT_CONFIRM_WORDS = List.of(
            "可以", "好的", "确认", "同意", "继续", "嗯", "行", "ok", "是的", "对",
            "不用问我", "直接执行", "好", "开始"
    );

    /**
     * 判断用户消息是否为短确认/闲聊消息
     * <p>
     * 此类消息（如"可以""好的"）语义信息极少，RAG 检索往往命中无关片段（如"笔记本进水"），
     * 注入后反而干扰模型对上下文的理解，直接跳过检索。
     *
     * @param content 用户消息内容
     * @return 是否为短确认消息
     */
    private boolean isShortConfirmation(String content) {
        if (content == null || content.trim().length() > 8) {
            return false;
        }
        String lower = content.trim().toLowerCase();
        return SHORT_CONFIRM_WORDS.stream().anyMatch(lower::contains);
    }

    /**
     * 检测用户消息是否命中「重装系统」意图
     *
     * @param content 用户消息内容
     * @return 是否命中
     */
    private boolean hasReinstallIntent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String lower = content.toLowerCase();
        return REINSTALL_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * 检测用户消息是否命中「卸载软件」意图
     *
     * @param content 用户消息内容
     * @return 是否命中
     */
    private boolean hasUninstallIntent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String lower = content.toLowerCase();
        return UNINSTALL_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * 检测用户消息是否命中「磁盘清理」意图
     */
    private boolean hasDiskCleanIntent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String lower = content.toLowerCase();
        return DISK_CLEAN_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * 检测用户消息是否命中「运行库修复」意图
     */
    private boolean hasVcRedistIntent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String lower = content.toLowerCase();
        return VCREDIST_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * 检测用户消息是否命中「压力测试」意图
     */
    private boolean hasStressIntent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String lower = content.toLowerCase();
        return STRESS_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * 后端仲裁：代跑 verifyUninstallApp 补验。
     * <p>
     * 从用户消息中提取软件名（appName）作为入参；提取不到时用整条消息兜底。
     * 只读校验，不删除任何文件。
     *
     * @param userContent 用户消息内容
     * @param sessionId   会话 ID
     * @return verifyUninstallApp 返回的结构化 JSON 文本
     */
    private String tryRunVerify(String userContent, Long sessionId) {
        String appName = extractAppName(userContent);
        try {
            return uninstallVerifyToolkit.verifyUninstallApp(appName, null);
        } catch (Exception e) {
            log.error("会话 {} 代跑 verifyUninstallApp 失败: app={}", sessionId, appName, e);
            return "{\"app\":\"" + appName + "\",\"hasSnapshot\":false,\"clean\":false,\"note\":\"代跑校验失败: "
                    + e.getMessage() + "\"}";
        }
    }

    /**
     * 从用户消息中提取软件名（appName）。
     * <p>
     * 规则：定位"卸载/删除/删掉/卸掉"等关键词，取其后紧跟的一段非空白文本作为软件名；
     * 若关键词后无有效内容，则返回整条消息（trim 后）作为兜底。
     *
     * @param content 用户消息内容
     * @return 提取到的软件名
     */
    private String extractAppName(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String trimmed = content.trim();
        String[] markers = {"卸载", "删除软件", "删掉", "卸掉", "删除", "remove", "uninstall"};
        for (String marker : markers) {
            int idx = trimmed.toLowerCase().indexOf(marker.toLowerCase());
            if (idx >= 0) {
                String after = trimmed.substring(idx + marker.length()).trim();
                // 去掉结尾的标点/语气词，取第一段
                after = after.replaceAll("[。！？!?，,、\\s]+$", "");
                if (!after.isBlank()) {
                    return after;
                }
            }
        }
        return trimmed;
    }

    /**
     * 组装重装系统图文引导内容
     * <p>
     * 将 ReinstallGuideEnum 各阶段按执行顺序拼接，作为一条系统消息注入，
     * 引导 LLM 按标准流程输出。
     *
     * @return 重装系统引导文本
     */
    private String buildReinstallGuide() {
        StringBuilder sb = new StringBuilder();
        sb.append("【重装系统专用引导】用户当前有重装系统的需求。请严格按照以下流程引导用户，")
                .append("每一步完成确认后再进入下一步，不要跳步，不要代替用户执行任何危险操作。\n\n");
        sb.append(ReinstallGuideEnum.ENV_CHECK.getContent()).append("\n\n");
        sb.append(ReinstallGuideEnum.BACKUP_REMIND.getContent()).append("\n\n");
        sb.append(ReinstallGuideEnum.DOWNLOAD_MCT.getContent()).append("\n\n");
        sb.append(ReinstallGuideEnum.MAKE_USB_GUIDE.getContent()).append("\n\n");
        sb.append(ReinstallGuideEnum.INSTALL_GUIDE.getContent());
        return sb.toString();
    }

    /**
     * 组装知识库检索结果的系统提示
     * <p>
     * 将 RAG 命中片段包装为系统消息，要求 LLM 优先参考知识库内容回答，
     * 并强调知识库可能不完整，避免模型编造知识库中不存在的信息。
     *
     * @param knowledge 命中的知识片段列表
     * @return 可注入的系统提示文本
     */
    private String buildKnowledgePrompt(List<String> knowledge) {
        StringBuilder sb = new StringBuilder(PromptEnum.KNOWLEDGE_BASE_REFERENCE.getContent());
        for (String k : knowledge) {
            sb.append("------\n").append(k).append("\n");
        }
        return sb.toString();
    }
    /**
     * 调用模型
     * @param chatClient ChatClient
     * @param messages 消息列表
     * @param sessionId 会话ID
     * @param invokedTools 调用的工具列表
     * @return 模型返回的消息
     */
    private ChatResponse callModel(ChatClient chatClient,
                             List<Message> messages,
                             Long sessionId,
                             List<String> invokedTools) {
        log.info("会话 {} 调用模型 invokedTools={}", sessionId, invokedTools);
        ChatResponse response = chatClient.prompt()
                .messages(messages)
                .toolContext(Map.of("sessionId", sessionId))
                .call()
                .chatResponse();
        return response;
    }
}

