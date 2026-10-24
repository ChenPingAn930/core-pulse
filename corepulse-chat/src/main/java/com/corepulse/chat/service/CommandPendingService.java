package com.corepulse.chat.service;

import com.corepulse.chat.model.vo.PendingCommandVO;
import com.corepulse.domain.entity.ChatMessage;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待确认命令的统一门面：查询、超时判定、超时收敛
 * <p>
 * 为什么把这些能力收在一个 Bean 里：待确认记录有四个消费方——
 * 前端补充查询、用户点确认、模型组装上下文、定时扫描。
 * 如果各自写一份 "status=PENDING" 的查询条件，只要有一处漏掉超时判断，
 * 就会读到"其实是过期记录"的假待确认项。
 */
public interface CommandPendingService {

    /**
     * 查询会话内仍然有效的待确认命令（只读，不会改状态）
     * <p>
     * 为什么刻意做成只读：调用方之一是 {@code ChatServiceImpl.send()}，它整个方法在事务里，
     * 要横跨两轮 LLM 调用（几十秒）。如果在里面执行"把超时记录改成 EXPIRED"这种范围 UPDATE，
     * 事务会一直持有索引上的间隙锁，而工具侧的 savePending 是用 REQUIRES_NEW 另开连接插同表，
     * 插入意向锁会等父事务的间隙锁 —— 父等子、子等父，直到 innodb_lock_wait_timeout（默认 50 秒）报错。
     * 超时收敛交给 expireTimeoutOfSession / expireIfTimeout / expireBatch 这三个"不在长事务里"的入口。
     *
     * @param sessionId 会话 ID
     * @return 按创建时间正序的有效待确认列表（created_at 早于时限的已被过滤），可能为空
     */
    List<PendingCommandVO> listValidPending(Long sessionId);

    /**
     * 收敛会话内已超时的待确认命令（写操作）
     * <p>
     * 只允许在没有长事务的入口调用：WebSocket 建连、Controller 请求、定时任务。
     * 这些场景每条 SQL 自动提交，锁持有时间是毫秒级，不会把后续的命令写入堵死。
     *
     * @param sessionId 会话 ID
     * @return 本次被收敛的记录数
     */
    int expireTimeoutOfSession(Long sessionId);

    /**
     * 把一条已超时的待确认命令置为 EXPIRED（幂等）
     *
     * @param messageId 命令记录的消息 ID
     * @return true 表示本次调用真的把它收敛了；false 表示它不存在、已不是 PENDING 或还没超时
     */
    boolean expireIfTimeout(Long messageId);

    /**
     * 批量收敛所有超时的待确认命令（定时任务用）
     *
     * @param cutoff 超时截止时间，创建时间早于它的 PENDING 记录会被收敛
     * @return 本次真正被收敛的记录（调用方据此逐条推送状态事件）
     */
    List<ChatMessage> expireBatch(LocalDateTime cutoff);

    /**
     * 计算超时截止时间（now - 配置的确认时限）
     *
     * @return 截止时间；创建时间早于该值的 PENDING 视为已超时
     */
    LocalDateTime timeoutCutoff();

    /**
     * 查询会话内当前是否还有有效待确认命令（模型口头确认校验用）
     *
     * @param sessionId 会话 ID
     * @return true 表示界面上确实存在可点击的确认卡片
     */
    boolean hasValidPending(Long sessionId);
}
