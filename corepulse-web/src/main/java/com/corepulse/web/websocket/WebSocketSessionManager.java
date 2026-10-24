package com.corepulse.web.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

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

    /**
     * 单条消息最长发送时间 / 最大缓冲（字节）
     * <p>
     * 给 ConcurrentWebSocketSessionDecorator 用：它把同一个 WebSocketSession 的发送串行化。
     * 消息是多个线程发出的（对话线程推 thinking、命令线程推 command_status、
     * 播报线程推 assistant_reply），底层 Tomcat 会话不是线程安全的，并发写会抛
     * "The remote endpoint was in state [TEXT_FULL_WRITING]"，那条消息就静默丢了。
     */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    // 注册 WebSocket 会话
    public void register(Long chatSessionId, WebSocketSession session) {
        // 关键：存装饰后的会话而不是原始会话，保证并发推送排队发送，而不是抛异常丢掉
        sessions.put(chatSessionId,
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT));
        log.info("WebSocket 连接注册: chatSessionId={}", chatSessionId);
    }

    public void remove(Long chatSessionId) {
        sessions.remove(chatSessionId);
    }
    /**
     * 推送消息
     * <p>
     * 返回值不是装饰：调用方需要知道"这条消息到底有没有发出去"。
     * 以前这里静默 return，前端没连上时和发送成功在日志上完全没区别，
     * 排查"后端说推了、用户说没收到"只能靠猜。
     *
     * @return true 表示已交给 WebSocket 会话写出；false 表示该会话没连接、已关闭或写出异常
     */
    public boolean send(Long chatSessionId, Object payload) {
        WebSocketSession session = sessions.get(chatSessionId);
        if (session == null) {
            log.debug("WebSocket 推送跳过: 会话未连接, chatSessionId={}", chatSessionId);
            return false;
        }
        if (!session.isOpen()) {
            log.warn("WebSocket 推送跳过: 连接已关闭, chatSessionId={}", chatSessionId);
            return false;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            return true;
        } catch (Exception e) {
            log.warn("WebSocket 推送失败: chatSessionId={}, error={}", chatSessionId, e.getMessage());
            return false;
        }
    }
}
