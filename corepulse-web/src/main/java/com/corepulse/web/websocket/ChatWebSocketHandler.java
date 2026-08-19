package com.corepulse.web.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;

/**
 * 聊天 WebSocket: ws://localhost:8080/ws/chat?sessionId={chatSessionId}
 * 服务端推送:
 * - 对话过程事件: chat_thinking / tool_started / tool_result
 * - 异步任务事件: tool_progress / tool_finished / confirm_request
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketSessionManager sessionManager;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long chatSessionId = parseSessionId(session.getUri());
        if (chatSessionId != null) {
            sessionManager.register(chatSessionId, session);
        } else {
            log.warn("连接缺少 sessionId 参数, 拒绝: {}", session.getUri());
            session.close(CloseStatus.BAD_DATA);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // M1 预留: 心跳。客户端发 {"type":"ping"} -> pong
        if (message.getPayload().contains("\"ping\"")) {
            session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long chatSessionId = parseSessionId(session.getUri());
        if (chatSessionId != null) {
            sessionManager.remove(chatSessionId);
        }
    }

    private Long parseSessionId(URI uri) {
        try {
            String query = uri.getQuery();
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if ("sessionId".equals(kv[0])) {
                    return Long.parseLong(kv[1]);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
