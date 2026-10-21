package com.corepulse.web.websocket;

import com.corepulse.chat.event.ChatEventPublisher;
import com.corepulse.common.event.CommandConfirmNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话过程事件 -> WebSocket 推送实现(chat 域接口的落地实现)
 * <p>
 * 前端未连接该会话的 WebSocket 时静默丢弃，不影响 HTTP 对话主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketChatEventPublisher implements ChatEventPublisher, CommandConfirmNotifier {

    private final WebSocketSessionManager sessionManager;

    @Override
    public void publishThinking(Long sessionId, String message) {
        if (sessionId == null) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "chat_thinking");
        event.put("sessionId", sessionId);
        event.put("message", message);
        sessionManager.send(sessionId, event);
    }

    @Override
    public void publishToolStarted(Long sessionId, String toolName, String args) {
        if (sessionId == null) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "tool_started");
        event.put("sessionId", sessionId);
        event.put("toolName", toolName);
        event.put("args", args);
        sessionManager.send(sessionId, event);
    }

    @Override
    public void publishToolResult(Long sessionId, String toolName, String summary) {
        if (sessionId == null) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "tool_result");
        event.put("sessionId", sessionId);
        event.put("toolName", toolName);
        event.put("summary", summary);
        sessionManager.send(sessionId, event);
    }

    /**
     * 推送"命令待确认"事件
     * <p>
     * 命令记录已经用独立事务提交，前端收到事件后可立即渲染确认卡片并调用确认接口。
     */
    @Override
    public void publishCommandConfirm(Long sessionId, Long messageId, String command) {
        if (sessionId == null || messageId == null) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "command_confirm");
        event.put("sessionId", sessionId);
        event.put("messageId", messageId);
        event.put("command", command);
        sessionManager.send(sessionId, event);
    }

    /**
     * 推送"命令状态变化"事件
     * <p>
     * 前端收到后按 messageId 更新对应确认卡片：EXECUTING 显示执行中并开始计时，
     * EXECUTED / FAILED 停止计时并展示结果摘要。
     * summary 已在工具侧截断，避免一次推送把整段命令输出塞进 WebSocket 帧。
     */
    @Override
    public void publishCommandStatus(Long sessionId, Long messageId, String status,
                                     String summary, Long costMs) {
        if (sessionId == null || messageId == null) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "command_status");
        event.put("sessionId", sessionId);
        event.put("messageId", messageId);
        event.put("status", status);
        event.put("summary", summary);
        event.put("costMs", costMs);
        sessionManager.send(sessionId, event);
    }
}
