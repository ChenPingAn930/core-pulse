package com.corepulse.web.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话管理: chatSessionId -> WebSocketSession
 */
@Slf4j
@Component
public class WebSocketSessionManager {

    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    // 注册 WebSocket 会话
    public void register(Long chatSessionId, WebSocketSession session) {
        sessions.put(chatSessionId, session);
        log.info("WebSocket 连接注册: chatSessionId={}", chatSessionId);
    }

    public void remove(Long chatSessionId) {
        sessions.remove(chatSessionId);
    }
    // 推送消息
    public void send(Long chatSessionId, Object payload) {
        WebSocketSession session = sessions.get(chatSessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.warn("WebSocket 推送失败: {}", e.getMessage());
        }
    }
}
