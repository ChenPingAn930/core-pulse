package com.corepulse.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    private final ChatClient chatClient;
    private final SessionService sessionService;
    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final KnowledgeService knowledgeService;
    private final ChatEventPublisher chatEventPublisher;

    /**
     * 发送消息 - AI 对话核心流程
     * <p>
     * 步骤：获取/创建会话 -> 自动生成标题 -> 保存用户消息 -> 构建上下文 -> 调用 LLM -> 保存回复
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
        String reply;
        java.util.List<String> invokedTools = new java.util.ArrayList<>();
        RecordingToolCallingManager.setCurrentSession(session.getId());
        try {
            // 调用 LLM
            ChatResponse chatResponse = chatClient.prompt()
                    .messages(messages)
                    .toolContext(Map.of("sessionId", session.getId()))
                    // 同步调用
                    .call()
                    .chatResponse();

            // 记录本次回复所基于的工具调用（工具调用信息在 AssistantMessage 的 toolCalls 中）
            chatResponse.getResults().forEach(result -> {
                org.springframework.ai.chat.messages.AssistantMessage output = result.getOutput();
                // 判断输出是否为空，避免空指针异常 以及 判断 toolCalls 是否为空，避免空指针异常
                // 注意output.getToolCalls()不是指代的工具调用，而是指代的这里的对象是否被创建
                if (output != null && output.getToolCalls() != null) {
                    // 获取工具调用名称
                    output.getToolCalls().forEach(tc -> invokedTools.add(tc.name()));
                }
            });
            // 获取最终回复
            reply = chatResponse.getResult() != null ? chatResponse.getResult().getOutput().getText() : null;
        } catch (Exception e) {
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

        //1.拿到摘要
        String summary = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getId, sessionId))
                .getSummary();
        if (summary == null || summary.isBlank()) {
            summary = "无";
        }
        //2.拼接摘要和用户消息
        List<Message> summaryMessages = new ArrayList<>();

        summaryMessages.add(new UserMessage(
                "旧摘要：\n" + (summary == null ? "无" : summary)
                        + "\n\n本轮用户消息：\n" + content
        ));

        summaryMessages.add(new AssistantMessage(reply));
        //3.调用LLM生成摘要；摘要失败不能影响本轮正式回复
        try {
            ChatResponse summaryResponse = chatClient.prompt()
                    .messages(summaryMessages)
                    .system(PromptEnum.SUMMARY_GENERATION.getContent())
                    .call()
                    .chatResponse();
            log.info("会话 {} 摘要生成完成, summaryResponse={}", session.getId(), summaryResponse);

            String newSummary = summaryResponse.getResult()
                    .getOutput()
                    .getText();
            if (newSummary == null || newSummary.isBlank()) {
                log.warn("会话 {} 摘要内容为空，跳过更新", session.getId());
            } else {
                //4.更新摘要
                ChatSession sessionForUpdate = sessionMapper.selectOne(
                        new LambdaQueryWrapper<ChatSession>()
                                .eq(ChatSession::getId, sessionId));
                if (sessionForUpdate == null) {
                    log.warn("会话 {} 不存在，跳过摘要更新", session.getId());
                } else {
                    sessionForUpdate.setSummary(newSummary.trim());
                    int updated = sessionMapper.updateById(sessionForUpdate);
                    if (updated > 0) {
                        log.info("会话 {} 更新摘要成功, newSummary={}",
                                session.getId(), newSummary);
                    } else {
                        log.warn("会话 {} 更新摘要失败，未更新任何记录", session.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("会话 {} 摘要生成或更新失败，跳过本次摘要更新",
                    session.getId(), e);
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
     */
    private void saveMessage(Long sessionId, String role, String content, String toolName, Long taskId) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setToolName(toolName);
        msg.setTaskId(taskId);
        messageMapper.insert(msg);
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
        StringBuilder sb = new StringBuilder();
        sb.append("【维修知识库参考】以下是从本地维修知识库检索到的相关资料。你必须严格遵守以下规则：\n")
                .append("1. 优先依据这些资料回答，回答要专业、准确、通俗易懂。\n")
                .append("2. 凡涉及硬件插拔、拆机、断电、短路等操作，必须【完整、逐字】保留知识库中给出的安全操作细节，")
                .append("例如「按下内存插槽两端的卡扣拔出内存」「断电后打开机箱」「释放残余电量」「橡皮擦擦拭金手指」「听到卡哒声即安装到位」「佩戴防静电手环」等，")
                .append("严禁省略、简化或自己概括这些关键动作。\n")
                .append("3. 【风险警告必须转述，这是最高优先级】：知识库中出现的任何以「⚠️ 风险警告」「风险警告」开头的段落，")
                .append("你必须【在向用户给出任何对应操作步骤的同时，把该风险警告的内容完整、逐字转述给用户】，")
                .append("例如「必须先拔掉电源线并长按开机键10秒释放残余电量」「建议佩戴防静电手环或先触摸金属物体释放静电」「切勿带电操作」「开箱可能使保修失效」等。")
                .append("不得省略、不得只在末尾一笔带过、不得只提示而不给具体内容。\n")
                .append("4. 知识库明确标注为「进阶操作」或「高风险」的步骤，必须在回答中同步提示风险，并提醒用户量力而行、必要时送修。\n")
                .append("5. 不要臆造知识库中不存在的操作步骤；若知识库未覆盖用户问题，则用通用知识谨慎回答并说明这是通用建议。\n\n");
        for (String k : knowledge) {
            sb.append("------\n").append(k).append("\n");
        }
        return sb.toString();
    }
}
