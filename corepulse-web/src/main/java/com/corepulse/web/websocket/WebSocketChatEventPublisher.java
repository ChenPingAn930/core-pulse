package com.corepulse.web.websocket;

import com.corepulse.chat.event.ChatEventPublisher;
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
public class WebSocketChatEventPublisher implements ChatEventPublisher {

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
}
