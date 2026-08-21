package com.corepulse.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.corepulse.chat.Enum.PromptEnum;
import com.corepulse.chat.Enum.ReinstallGuideEnum;
import com.corepulse.chat.config.RecordingToolCallingManager;
import com.corepulse.chat.event.ChatEventPublisher;
import com.corepulse.chat.mapper.ChatMessageMapper;
import com.corepulse.chat.mapper.ChatSessionMapper;
import com.corepulse.chat.model.dto.ChatSendRequest;
import com.corepulse.chat.model.vo.ChatSendVO;
import com.corepulse.chat.rag.KnowledgeService;
import com.corepulse.chat.service.ChatService;
import com.corepulse.chat.service.SessionService;
import com.corepulse.domain.entity.ChatMessage;
import com.corepulse.domain.entity.ChatSession;
import com.corepulse.domain.enums.MessageRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
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

    private static final int MAX_HISTORY = 20;

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

        // 3. 保存用户消息
        saveMessage(session.getId(), MessageRole.USER.getValue(), content, null, null);

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
        try {
            // 2次重试
            for (int i = 0; i < 3; i++) {
                try {
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
                reply = chatResponse.getResult() != null ? chatResponse.getResult().getOutput().getText() : null;
                if (reply != null && !reply.isBlank()) {
                    log.info("会话 {} 获取到回复: {}", session.getId(), reply);
                    success = true;
                    break;
                }
            }
                catch (Exception e) {
                    log.warn("第 {} 次调用 LLM 失败,重试中...", i+1, e);
                }
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
            // 无论成功失败都需 drain，防止线程复用时串数据
            saveToolRecords(session.getId());
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

        // 7. 保存 AI 回复
        saveMessage(session.getId(), MessageRole.ASSISTANT.getValue(), reply, null, null);
        log.info("会话 {} 回复完成, replyLen={}", session.getId(), reply.length());

        // 8. 摘要只处理上次游标之后的消息；摘要失败时不推进游标
        ChatSession currentSession = sessionMapper.selectById(sessionId);
        Long lastSummarizedMessageId = currentSession.getLastSummarizedMessageId() == null
                ? 0L : currentSession.getLastSummarizedMessageId();
        // 获取未摘要的消息
        List<ChatMessage> unsummarizedMessages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .gt(ChatMessage::getId, lastSummarizedMessageId)
                        .orderByAsc(ChatMessage::getId)
        );

        log.info("会话 {} 摘要游标={}, 未摘要消息数={}",
                session.getId(), lastSummarizedMessageId, unsummarizedMessages.size());
        if (unsummarizedMessages.size() < 20) {
            log.info("会话 {} 未摘要消息不足20条，不生成摘要", session.getId());
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
            // 调用摘要生成模型
            ChatResponse summaryResponse = primaryChatClient.prompt()
                    .messages(summaryMessages)
                    .system(PromptEnum.SUMMARY_GENERATION.getContent())
                    .call()
                    .chatResponse();
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

        /* 历史消息（最近 MAX_HISTORY 条，按 ID 正序）*/
        List<ChatMessage> history = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByDesc(ChatMessage::getId)
                        .last("LIMIT " + MAX_HISTORY));
        // 倒序，保证按时间正序
        Collections.reverse(history);
        // 组装历史消息
        for (ChatMessage m : history) {
            // 拿到消息角色 user / assistant / system / tool */
            String role = m.getRole();
            if (MessageRole.USER.getValue().equals(role)) {
                messages.add(new UserMessage(m.getContent()));
            } else if (MessageRole.ASSISTANT.getValue().equals(role)) {
                messages.add(new AssistantMessage(m.getContent()));
            } else if (MessageRole.TOOL.getValue().equals(role)) {
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
     * 保存本轮对话中执行过的工具调用结果（role=tool）
     * <p>
     * 工具结果由 RecordingToolCallingManager 在 ChatClient 内部工具循环中记录，
     * 这里取出后截断入库，保证历史上下文不被大输出撑爆。
     *
     * @param sessionId 所属会话 ID
     */
    private void saveToolRecords(Long sessionId) {
        for (RecordingToolCallingManager.ToolCallRecord record : RecordingToolCallingManager.drainRecords()) {
            String result = record.result() == null ? "" : record.result();
            if (result.length() > MAX_TOOL_RESULT_LENGTH) {
                result = result.substring(0, MAX_TOOL_RESULT_LENGTH) + "...(已截断)";
            }
            saveMessage(sessionId, MessageRole.TOOL.getValue(), result, record.toolName(), null);
        }
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
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setToolName(toolName);
        msg.setTaskId(taskId);
        int insert = messageMapper.insert(msg);
        if (insert > 0)
            log.info("会话 {} 保存消息成功, role={}, content={}, toolName={}, taskId={}",
                    sessionId, role, content, toolName, taskId);
        else
            log.warn("会话 {} 保存消息失败，未插入任何记录", sessionId);
        return msg.getId();
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
        log.info("会话 {} 调用模型, messages={}, invokedTools={}", sessionId, messages, invokedTools);
        ChatResponse response = chatClient.prompt()
                .messages(messages)
                .toolContext(Map.of("sessionId", sessionId))
                .call()
                .chatResponse();
        return response;
    }
}

