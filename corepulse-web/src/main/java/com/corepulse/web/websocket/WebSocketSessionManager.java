package com.corepulse.web.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话管理: chatSessionId -> WebSocketSession
 */
@Slf4j
@Component
public class WebSocketSessionManager {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * 每个 chatSessionId 当前由哪条原始连接（WebSocketSession#getId）拥有。
     * <p>
     * 为什么需要属主：一个会话只保留一条连接，页面刷新/断线重连时新连接会覆盖旧的，
     * 但旧连接的关闭回调可能比新连接注册更晚到达。如果关闭时不校验属主，
     * 旧连接会把刚注册好的新连接从 map 里删掉，此后所有推送都发不出去，
     * 前端表现就是"卡片补推后正常、实时事件全丢"。
     */
    private final Map<Long, String> sessionOwners = new ConcurrentHashMap<>();

    /**
     * 未送达的助手回复（仅 assistant_reply 使用）。
     * <p>
     * 这类事件没有 HTTP 响应承接，也没有历史接口轮询兜底：一旦发送时前端正好在重连，
     * 用户只能刷新页面才能看到模型说了什么。缓冲几帧并在连接建立后补推，
     * 可以在不改前端的前提下把这个窗口补上。
     */
    private final Map<Long, Deque<BufferedFrame>> pendingReplies = new ConcurrentHashMap<>();

    /** 单条消息最长发送时间 / 最大缓冲（字节） */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    /** 每个会话最多缓冲的回复条数：只补最近的，更早的已经在库里，刷新即可看到 */
    private static final int MAX_BUFFERED_REPLIES = 3;

    /** 缓冲有效期：超过这个时间还没连上就不补了，避免用户几分钟后回来突然弹出一堆旧回复 */
    private static final long BUFFER_TTL_MS = 5 * 60 * 1000L;

    // 注册 WebSocket 会话
    public void register(Long chatSessionId, WebSocketSession session) {
        // 关键：存装饰后的会话而不是原始会话，保证并发推送排队发送，而不是抛异常丢掉
        sessions.put(chatSessionId,
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT));
        sessionOwners.put(chatSessionId, session.getId());
        log.info("WebSocket 连接注册: chatSessionId={}, rawSessionId={}", chatSessionId, session.getId());
        flushPendingReplies(chatSessionId);
    }

    /**
     * 注销 WebSocket 会话（按属主校验）
     * <p>
     * 只有"当前登记的连接"关闭时才真正移除；旧连接的迟到关闭回调直接忽略，
     * 否则会把重连后的新连接一起删掉。
     *
     * @param chatSessionId 业务会话 ID
     * @param rawSessionId  触发关闭的原始连接 ID
     */
    public void remove(Long chatSessionId, String rawSessionId) {
        String owner = sessionOwners.get(chatSessionId);
        if (owner != null && !owner.equals(rawSessionId)) {
            log.info("忽略旧连接的关闭回调: chatSessionId={}, 当前属主={}, 关闭连接={}",
                    chatSessionId, owner, rawSessionId);
            return;
        }
        sessions.remove(chatSessionId);
        sessionOwners.remove(chatSessionId);
        // 注意：不清 pendingReplies。推送失败的帧可能刚好赶上连接关闭回调，
        // 这里清掉就等于把它们永久丢弃；缓冲靠 TTL 与 flush 自然收敛
    }

    /**
     * 推送消息
     * <p>
     * 返回值不是装饰：调用方需要知道"这条消息到底有没有发出去"。
     * 以前这里静默 return，前端没连上时和发送成功在日志上完全没区别，排查"后端说推了、用户说没收到"只能靠猜。
     *
     * @return true 表示已交给 WebSocket 会话写出；false 表示该会话没连接、已关闭或写出异常
     */
    public boolean send(Long chatSessionId, Object payload) {
        WebSocketSession session = sessions.get(chatSessionId);
        if (session == null) {
            // 由 debug 提升为 warn：这条日志是"后端说推了、用户说没收到"的关键证据，
            // INFO 级别下必须看得到
            log.warn("WebSocket 推送跳过: 会话未连接, chatSessionId={}", chatSessionId);
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

    /**
     * 推送；发送不成功时进入短时缓冲，等该会话下次建连补推
     * <p>
     * 只给"没有其他通道兜底"的事件用（当前是 assistant_reply）。命令卡片有自己的补推与轮询，
     * 放进缓冲反而会和历史重放叠加出多张卡片。
     *
     * @param chatSessionId 业务会话 ID
     * @param payload       事件负载
     */
    public void sendOrBuffer(Long chatSessionId, Object payload) {
        if (chatSessionId == null || payload == null) {
            return;
        }
        if (send(chatSessionId, payload)) {
            return;
        }
        buffer(chatSessionId, payload);
    }

    /** 把未送达的帧放进缓冲：同会话最多 MAX_BUFFERED_REPLIES 条，超出丢最旧的 */
    private void buffer(Long chatSessionId, Object payload) {
        long now = System.currentTimeMillis();
        // 顺手清一遍全表：缓冲只在"发送失败"时写入，触发频率很低，
        // 在这里做过期清理可以避免长期不再重连的会话把内存占住
        purgeExpiredBuffers(now);
        Deque<BufferedFrame> queue = pendingReplies.computeIfAbsent(chatSessionId, k -> new ArrayDeque<>());
        int size;
        synchronized (queue) {
            purgeExpired(queue, now);
            while (queue.size() >= MAX_BUFFERED_REPLIES) {
                queue.pollFirst();
            }
            queue.addLast(new BufferedFrame(payload, System.currentTimeMillis()));
            size = queue.size();
        }
        log.warn("助手回复未送达, 进入补推缓冲: chatSessionId={}, buffered={}", chatSessionId, size);
    }

    /** 连接建立后按序补推缓冲；发送失败的帧放回缓冲，等下一次重连 */
    private void flushPendingReplies(Long chatSessionId) {
        Deque<BufferedFrame> queue = pendingReplies.get(chatSessionId);
        if (queue == null) {
            return;
        }
        List<BufferedFrame> frames = new ArrayList<>();
        synchronized (queue) {
            frames.addAll(queue);
            queue.clear();
        }
        long now = System.currentTimeMillis();
        int sent = 0;
        for (int i = 0; i < frames.size(); i++) {
            BufferedFrame frame = frames.get(i);
            if (now - frame.createdAt() > BUFFER_TTL_MS) {
                continue;
            }
            if (!send(chatSessionId, frame.payload())) {
                // 连上又立刻断了：把这一帧和后面没发的放回去，避免静默丢失
                for (int j = i; j < frames.size(); j++) {
                    buffer(chatSessionId, frames.get(j).payload());
                }
                break;
            }
            sent++;
        }
        log.info("WebSocket 建连补推助手回复: chatSessionId={}, 缓冲={}, 成功={}",
                chatSessionId, frames.size(), sent);
    }

    /** 清掉过期帧：用户几分钟后才回来时不该再弹旧回复 */
    private void purgeExpired(Deque<BufferedFrame> queue, long now) {
        while (!queue.isEmpty() && now - queue.peekFirst().createdAt() > BUFFER_TTL_MS) {
            queue.pollFirst();
        }
    }

    /** 清理所有会话的过期帧，并移除已经空的队列 */
    private void purgeExpiredBuffers(long now) {
        pendingReplies.forEach((sessionId, queue) -> {
            synchronized (queue) {
                purgeExpired(queue, now);
                if (queue.isEmpty()) {
                    pendingReplies.remove(sessionId, queue);
                }
            }
        });
    }

    /** 缓冲中的一条待补推帧：payload 在构造后不再修改，可直接复用 */
    private record BufferedFrame(Object payload, long createdAt) {
    }
}
