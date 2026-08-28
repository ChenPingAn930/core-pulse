package com.corepulse.chat.config;

import com.corepulse.chat.event.ChatEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 记录工具调用结果的 ToolCallingManager 装饰器
 * <p>
 * Spring AI 的 ChatClient 内部工具调用循环中，tool 结果只存活于当轮上下文，
 * 跨轮次即丢失（下一轮 LLM 只能靠 AI 回复文字猜测上一轮工具干了什么）。
 * <p>
 * 本类包装默认实现：每次 executeToolCalls 后把工具名与结果摘要记入 ThreadLocal，
 * 由 ChatServiceImpl 在一轮对话结束后取出并存入 t_chat_message（role=tool），
 * 使跨轮次上下文保持完整（如压测 taskId、扫描原始数据等多步流程关键信息）。
 * <p>
 * 同步聊天请求的工具执行与 save 在同一线程内完成，ThreadLocal 不会串请求。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingToolCallingManager implements ToolCallingManager {

    /** 当前线程本轮已执行的工具调用记录 */
    private static final ThreadLocal<List<ToolCallRecord>> CURRENT = ThreadLocal.withInitial(ArrayList::new);

    /** 当前线程本轮对话所属会话 ID（用于推送对话过程事件，sessionId 在 toolContext 中本接口拿不到） */
    private static final ThreadLocal<Long> SESSION_ID = new ThreadLocal<>();

    /** 推送给前端的参数/结果摘要最大长度 */
    private static final int MAX_EVENT_SUMMARY_LENGTH = 200;

    /** 委托对象：Spring AI 默认工具调用管理器 */
    private final ToolCallingManager delegate = DefaultToolCallingManager.builder().build();

    /** 对话过程事件发布器（WebSocket 落地实现在 web 模块） */
    private final ChatEventPublisher chatEventPublisher;

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        // 执行前推送 tool_started：此时 LLM 已决定调用哪些工具，名称与参数在输出消息的 toolCalls 中
        publishToolStarted(chatResponse);
        // 执行工具调用 拿结果
        ToolExecutionResult result = delegate.executeToolCalls(prompt, chatResponse);
        // conversationHistory() 返回的是完整累积历史（含此前各轮内部循环的旧工具响应），
        // 只记录本次新增的：跳过数量 = 执行前 prompt 中已存在的 ToolResponseMessage 数，
        // 否则多轮工具调用时旧结果会被反复记录，造成数据库重复入库、历史窗口被重复记录挤占
        int existing = countToolResponses(prompt.getInstructions());
        int seen = 0;
        //遍历所有消息
        for (Message message : result.conversationHistory()) {
            // 如果是工具响应消息
            if (message instanceof ToolResponseMessage toolResponse) {
                // 跳过已存在的工具响应
                if (seen++ < existing) {
                    continue;
                }
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    CURRENT.get().add(new ToolCallRecord(response.name(), response.responseData()));
                    log.info("记录工具调用结果: tool={}, 结果长度={}",
                            response.name(),
                            response.responseData() != null ? response.responseData().length() : 0);
                    // 执行后推送 tool_result + 重新进入思考阶段，让前端实时看到工具执行完毕、AI 正在分析
                    publishToolResult(response);
                    chatEventPublisher.publishThinking(SESSION_ID.get(), "正在分析工具执行结果");
                }
            }
        }
        return result;
    }

    /** 统计消息列表中 ToolResponseMessage 的数量（即执行前已存在的工具响应） */
    private int countToolResponses(List<Message> messages) {
        int count = 0;
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage) {
                count++;
            }
        }
        return count;
    }

    /**
     * 取出并清空当前线程本轮累积的工具调用记录
     * <p>
     * 由 ChatServiceImpl 在一轮对话结束后调用；无论对话是否发生工具调用都应调用，
     * 以保证 ThreadLocal 被清理，防止线程复用时串数据。
     *
     * @return 本轮的工具调用记录列表（无则空列表）
     */
    public static List<ToolCallRecord> drainRecords() {
        List<ToolCallRecord> records = new ArrayList<>(CURRENT.get());
        CURRENT.remove();
        return records;
    }

    /**
     * 设置当前线程本轮对话所属会话 ID
     * <p>
     * 由 ChatServiceImpl 在调用 ChatClient 前设置；同步聊天请求的工具执行与请求处理在同一线程，
     * ThreadLocal 不会串请求。
     *
     * @param sessionId 会话 ID
     */
    public static void setCurrentSession(Long sessionId) {
        SESSION_ID.set(sessionId);
    }

    /** 清理当前线程的会话 ID，防止线程复用时串数据（由 ChatServiceImpl 在 finally 中调用） */
    public static void clearCurrentSession() {
        SESSION_ID.remove();
    }

    /** 执行前推送 tool_started 事件（事件推送失败不影响工具执行主流程） */
    private void publishToolStarted(ChatResponse chatResponse) {
        Long sessionId = SESSION_ID.get();
        if (sessionId == null || chatResponse.getResult() == null) {
            return;
        }
        try {
            var output = chatResponse.getResult().getOutput();
            if (output == null || output.getToolCalls() == null) {
                return;
            }
            output.getToolCalls().forEach(tc ->
                    chatEventPublisher.publishToolStarted(sessionId, tc.name(), truncate(tc.arguments())));
        } catch (Exception e) {
            log.warn("推送 tool_started 事件失败: {}", e.getMessage());
        }
    }

    /** 执行后推送 tool_result 事件（事件推送失败不影响工具记录主流程） */
    private void publishToolResult(ToolResponseMessage.ToolResponse response) {
        Long sessionId = SESSION_ID.get();
        if (sessionId == null) {
            return;
        }
        try {
            chatEventPublisher.publishToolResult(sessionId, response.name(), truncate(response.responseData()));
            log.info("推送 tool_result 事件: tool={}, 结果长度={}",
                    response.name(),
                    response.responseData() != null ? response.responseData().length() : 0);
        } catch (Exception e) {
            log.warn("推送 tool_result 事件失败: {}", e.getMessage());
        }
    }

    /** 截断超长文本作为前端展示摘要 */
    private String truncate(String text) {
        if (text == null || text.length() <= MAX_EVENT_SUMMARY_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_EVENT_SUMMARY_LENGTH) + "...";
    }

    /** 单次工具调用记录：工具名 + 结果文本 */
    public record ToolCallRecord(String toolName, String result) {
    }
}
