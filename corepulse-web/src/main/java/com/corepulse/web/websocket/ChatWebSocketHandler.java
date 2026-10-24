package com.corepulse.web.websocket;

import com.corepulse.chat.model.vo.PendingCommandVO;
import com.corepulse.chat.service.CommandPendingService;
import com.corepulse.common.event.CommandConfirmNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;

/**
 * 聊天 WebSocket: ws://localhost:8080/ws/chat?sessionId={chatSessionId}
 * 服务端推送:
 * - 对话过程事件: chat_thinking / tool_started / tool_result
 * - 异步任务事件: tool_progress / tool_finished / confirm_request
 * - 命令确认事件: command_confirm / command_status
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketSessionManager sessionManager;

    /** 待确认命令查询：连接建立时补推卡片用 */
    private final CommandPendingService commandPendingService;

    /** 卡片事件推送（与工具侧使用同一个推送器，事件格式保持一致） */
    private final CommandConfirmNotifier commandConfirmNotifier;

    // 连接成功时注册 WebSocket 会话
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 从 URI 中解析 sessionId
        Long chatSessionId = parseSessionId(session.getUri());
        if (chatSessionId != null) {
            sessionManager.register(chatSessionId, session);
            // 注册后立刻补推未确认的卡片：卡片原本只靠一次性事件渲染，
            // 页面刷新、断线重连时那条事件早已过去，用户就只看到"请在卡片上确认"的文字而找不到卡片
            repushPendingCards(chatSessionId);
        } else {
            log.warn("连接缺少 sessionId 参数, 拒绝: {}", session.getUri());
            session.close(CloseStatus.BAD_DATA);
        }
    }

    /** 补推该会话仍然有效的待确认卡片；推送失败只记日志，不影响连接本身 */
    private void repushPendingCards(Long chatSessionId) {
        try {
            // 建连回调没有事务包裹，这里的收敛是自动提交的，不会长时间持锁
            commandPendingService.expireTimeoutOfSession(chatSessionId);
            List<PendingCommandVO> pending = commandPendingService.listValidPending(chatSessionId);
            for (PendingCommandVO item : pending) {
                commandConfirmNotifier.publishCommandConfirm(
                        chatSessionId, item.getMessageId(), item.getCommand());
            }
            if (!pending.isEmpty()) {
                log.info("WebSocket 建连补推待确认卡片: sessionId={}, count={}", chatSessionId, pending.size());
            }
        } catch (Exception e) {
            log.warn("补推待确认卡片失败: sessionId={}, error={}", chatSessionId, e.getMessage());
        }
    }
    // 收到消息时预留: 心跳。客户端发 {"type":"ping"} -> pong
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // M1 预留: 心跳。客户端发 {"type":"ping"} -> pong
        if (message.getPayload().contains("\"ping\"")) {
            session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
        }
    }
    // 连接关闭时注销 WebSocket 会话
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long chatSessionId = parseSessionId(session.getUri());
        if (chatSessionId != null) {
            sessionManager.remove(chatSessionId);
        }
    }
    // 从 URI 中解析 sessionId
    // 请求格式: ws://localhost:8080/ws/chat?sessionId={chatSessionId}
    private Long parseSessionId(URI uri) {
        try {
            // 解析 query 参数 sessionId={chatSessionId}部分
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
