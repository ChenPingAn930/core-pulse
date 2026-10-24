package com.corepulse.chat.concurrent;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话级串行锁
 * <p>
 * 目的：同一个会话的 LLM 推理必须排队。一次 send() 会读历史 -> 调模型 -> 写回复，
 * 两个请求并发跑就会出现"双方都读到同一份旧历史、各自写入一条回复"的交叉，
 * 更糟的是模型 A 看不到模型 B 刚做完的动作，会重复执行或自相矛盾。
 * <p>
 * 为什么不用每个会话一把锁（Map&lt;Long, Lock&gt;）：
 * 会话数量无上限，锁对象只增不减就是内存泄漏；清理又很难做对（谁都不知道还有没有线程在等）。
 * 这里改用固定槽位（按 sessionId 取模），内存恒定；两个会话撞到同一槽位时只是多排一次队，
 * 属于可接受的伪冲突，不会影响正确性。
 * <p>
 * 为什么锁是 ReentrantLock 而不是 synchronized：需要 tryLock(超时) 的能力——
 * 后台播报任务抢不到锁时应该放弃，而不是无限期占着线程等用户把话说完。
 */
@Component
public class SessionChatLock {

    /** 槽位数量：按 64 取模，单机几十个活跃会话的冲突概率已经很低 */
    private static final int SLOT_COUNT = 64;

    private final ReentrantLock[] slots = new ReentrantLock[SLOT_COUNT];

    public SessionChatLock() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = new ReentrantLock();
        }
    }

    /**
     * 取某会话对应的锁
     * <p>
     * sessionId 为 null（新会话首条消息）时统一落到 0 号槽：
     * 新会话本来就没有历史可交叉，串行化只是顺带的结果，不会造成正确性问题。
     * <p>
     * 返回 ReentrantLock 而不是 Lock：调用方需要 hasQueuedThreads() 来判断
     * "有没有用户在排队"，从而让后台任务主动让路。
     */
    public ReentrantLock slotOf(Long sessionId) {
        int slot = sessionId == null ? 0 : Math.floorMod(sessionId.hashCode(), SLOT_COUNT);
        return slots[slot];
    }
}
