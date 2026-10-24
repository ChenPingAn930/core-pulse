package com.corepulse.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.corepulse.chat.model.vo.PendingCommandVO;
import com.corepulse.chat.service.CommandPendingService;
import com.corepulse.common.config.CommandConfirmProperties;
import com.corepulse.domain.entity.ChatMessage;
import com.corepulse.domain.enums.CommandStatus;
import com.corepulse.domain.mapper.ChatMessageMapper;
import com.corepulse.system.tool.ShellCommandExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 待确认命令门面实现
 * <p>
 * 超时判定统一走 SQL 条件（{@code created_at < cutoff}），不读实体的 getCreatedAt() 做 Java 比较：
 * 1. 插入时库里的 DEFAULT CURRENT_TIMESTAMP 不会回填到实体，实体时间可能为 null；
 * 2. 条件更新天然幂等——定时扫描和用户点击同时发生时，只有先到的那次能改成功。
 * <p>
 * 每次批量收敛限制扫描上限，避免历史脏数据一次性拉满内存；剩下的由下一轮扫描继续处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandPendingServiceImpl implements CommandPendingService {

    /** 单次扫描最多处理的记录数：数据量通常是个位数，这里只做兜底保护 */
    private static final int SWEEP_BATCH_SIZE = 200;

    private final ChatMessageMapper chatMessageMapper;

    private final CommandConfirmProperties commandConfirmProperties;

    @Override
    public List<PendingCommandVO> listValidPending(Long sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        LocalDateTime cutoff = timeoutCutoff();
        // 这里只做过滤、不做 UPDATE：本方法会被长事务里的 send() 调用，
        // 范围 UPDATE 拿到的间隙锁会挡住子事务（REQUIRES_NEW）对新命令记录的插入，
        // 见 CommandPendingService#listValidPending 的说明

        List<ChatMessage> pendingList = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getToolName, ShellCommandExecutor.TOOL_NAME)
                .eq(ChatMessage::getStatus, CommandStatus.PENDING.getValue())
                .ge(ChatMessage::getCreatedAt, cutoff)
                .orderByAsc(ChatMessage::getId));

        List<PendingCommandVO> result = new ArrayList<>(pendingList.size());
        LocalDateTime now = LocalDateTime.now();
        for (ChatMessage message : pendingList) {
            // 每条卡片按自己的创建时间算失效时刻：如果用 now + timeout，所有卡片都会显示"刚刚创建、5 分钟后失效"，
            // 前端倒计时就会全部重置，用户永远等不到倒计时结束
            LocalDateTime expiresAt = message.getCreatedAt() == null
                    ? now.plus(commandConfirmProperties.getConfirmTimeout())
                    : message.getCreatedAt().plus(commandConfirmProperties.getConfirmTimeout());
            result.add(PendingCommandVO.builder()
                    .messageId(message.getId())
                    .command(message.getContent())
                    .status(message.getStatus())
                    .createdAt(message.getCreatedAt())
                    .expiresAt(expiresAt)
                    .build());
        }
        return result;
    }

    @Override
    public boolean expireIfTimeout(Long messageId) {
        if (messageId == null) {
            return false;
        }
        int updated = chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .set(ChatMessage::getStatus, CommandStatus.EXPIRED.getValue())
                .eq(ChatMessage::getId, messageId)
                .eq(ChatMessage::getStatus, CommandStatus.PENDING.getValue())
                .lt(ChatMessage::getCreatedAt, timeoutCutoff()));
        if (updated > 0) {
            log.info("待确认命令已超时失效: messageId={}, timeout={}",
                    messageId, commandConfirmProperties.getConfirmTimeout());
        }
        return updated > 0;
    }

    @Override
    public List<ChatMessage> expireBatch(LocalDateTime cutoff) {
        if (cutoff == null) {
            return List.of();
        }
        List<ChatMessage> candidates = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getStatus, CommandStatus.PENDING.getValue())
                .lt(ChatMessage::getCreatedAt, cutoff)
                .orderByAsc(ChatMessage::getId)
                .last("LIMIT " + SWEEP_BATCH_SIZE));

        List<ChatMessage> expired = new ArrayList<>();
        for (ChatMessage candidate : candidates) {
            // 逐条条件更新：中途被用户点确认（已变 CONFIRMED）的记录不会被误置为 EXPIRED
            int updated = chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                    .set(ChatMessage::getStatus, CommandStatus.EXPIRED.getValue())
                    .eq(ChatMessage::getId, candidate.getId())
                    .eq(ChatMessage::getStatus, CommandStatus.PENDING.getValue())
                    .lt(ChatMessage::getCreatedAt, cutoff));
            if (updated > 0) {
                expired.add(candidate);
            }
        }
        return expired;
    }

    @Override
    public LocalDateTime timeoutCutoff() {
        return LocalDateTime.now().minus(commandConfirmProperties.getConfirmTimeout());
    }

    @Override
    public boolean hasValidPending(Long sessionId) {
        if (sessionId == null) {
            return false;
        }
        LocalDateTime cutoff = timeoutCutoff();
        Long count = chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getToolName, ShellCommandExecutor.TOOL_NAME)
                .eq(ChatMessage::getStatus, CommandStatus.PENDING.getValue())
                .ge(ChatMessage::getCreatedAt, cutoff));
        return count != null && count > 0;
    }

    @Override
    public int expireTimeoutOfSession(Long sessionId) {
        if (sessionId == null) {
            return 0;
        }
        try {
            int updated = chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                    .set(ChatMessage::getStatus, CommandStatus.EXPIRED.getValue())
                    .eq(ChatMessage::getSessionId, sessionId)
                    .eq(ChatMessage::getToolName, ShellCommandExecutor.TOOL_NAME)
                    .eq(ChatMessage::getStatus, CommandStatus.PENDING.getValue())
                    .lt(ChatMessage::getCreatedAt, timeoutCutoff()));
            if (updated > 0) {
                log.info("会话内待确认命令已超时失效: sessionId={}, count={}", sessionId, updated);
            }
            return updated;
        } catch (Exception e) {
            // 收敛失败不阻断查询：列表本身已经用 created_at >= cutoff 过滤，不会返回过期记录
            log.warn("会话内待确认命令超时收敛失败: sessionId={}", sessionId, e);
            return 0;
        }
    }
}
