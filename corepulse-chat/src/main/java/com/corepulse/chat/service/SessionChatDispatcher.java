package com.corepulse.chat.service;

import com.corepulse.chat.concurrent.SessionChatLock;
import com.corepulse.chat.model.dto.ChatSendRequest;
import com.corepulse.chat.model.vo.ChatSendVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话级对话调度器（串行化入口）
 * <p>
 * 所有会触发 LLM 推理的调用都必须从这里进，才能保证同一会话串行：
 * - 用户发消息（{@link #send}）：必须执行，抢不到锁就排队等；
 * - 后台任务（如命令执行完的播报，{@link #trySend}）：可以放弃，抢不到锁就直接跳过。
 * <p>
 * 为什么锁要放在这一层，而不是直接加在 ChatServiceImpl.send() 里：
 * send() 方法是 @Transactional 的，事务由 Spring 代理在"方法返回之后"提交。
 * 如果锁加在方法内部，finally 释放锁时事务还没提交，下一个拿到锁的请求
 * 会读到"上一轮回复还没写进去"的旧历史，串行化就白做了。
 * 放在这层（无事务方法）里，unlock 一定发生在事务提交之后。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionChatDispatcher {

    private final ChatService chatService;

    private final SessionChatLock sessionChatLock;

    /**
     * 发送消息（阻塞等待会话锁）
     * <p>
     * 用于用户真实发起的一轮对话：必须保证执行，排队等待是可以接受的代价，
     * 因为前端在等待期间本来就会显示"思考中"。
     */
    public ChatSendVO send(ChatSendRequest request) {
        ReentrantLock lock = sessionChatLock.slotOf(request.getSessionId());
        lock.lock();
        try {
            return chatService.send(request);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 尝试发送消息（最多等待指定秒数，抢不到锁返回 null）
     * <p>
     * 用于后台自动触发的播报轮次：它的价值远低于用户正在说的话，
     * 如果会话正被用户占用，等到锁再插一句话反而会打断用户的节奏，
     * 而且这类通知在用户下一轮对话时模型能从历史里看到，跳过也不会丢信息。
     *
     * @param request     发送请求
     * @param waitSeconds 最长等待秒数
     * @return 回复；抢不到锁或执行失败返回 null
     */
    public ChatSendVO trySend(ChatSendRequest request, long waitSeconds) {
        ReentrantLock lock = sessionChatLock.slotOf(request.getSessionId());
        boolean acquired;
        try {
            acquired = lock.tryLock(waitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // 恢复中断标记：线程池里的任务被要求停止时，不能把中断状态吞掉
            Thread.currentThread().interrupt();
            log.warn("等待会话锁被中断: sessionId={}", request.getSessionId());
            return null;
        }
        if (!acquired) {
            log.info("会话正忙，跳过本次后台播报: sessionId={}, source={}",
                    request.getSessionId(), request.getSource());
            return null;
        }
        // 抢到锁的瞬间如果已有用户在排队，立刻让路：
        // 后台播报只是补充说明，用户正在等的那一轮才是主线，不能让它排在播报后面
        if (lock.hasQueuedThreads()) {
            lock.unlock();
            log.info("检测到用户请求排队，后台任务主动让路: sessionId={}", request.getSessionId());
            return null;
        }
        try {
            return chatService.send(request);
        } catch (Exception e) {
            // 后台播报失败不影响任何既有事实（命令已执行、状态已落库），只记日志
            log.warn("后台播报失败: sessionId={}", request.getSessionId(), e);
            return null;
        } finally {
            lock.unlock();
        }
    }
}
