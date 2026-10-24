package com.corepulse.web.scheduler;

import com.corepulse.chat.service.CommandPendingService;
import com.corepulse.common.event.CommandConfirmNotifier;
import com.corepulse.domain.entity.ChatMessage;
import com.corepulse.domain.enums.CommandStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时命令收敛任务
 * <p>
 * 为什么要定时任务、而不是只靠"访问时惰性判定"：惰性判定只在有人访问时才生效。
 * 用户把页面开在那儿不动、也不发新消息时，卡片会一直转圈，界面上看不出"这条命令已经作废了"。
 * 定时扫描把状态收敛掉并推事件，卡片才能自动停止等待。
 * <p>
 * 为什么单个任务失败要自己 catch：调度线程里抛出的异常会打断后续调度，
 * 一条脏数据就能让整个清理任务永久停摆，比不清理更危险。
 * <p>
 * 当前前提是单实例部署；若将来多实例，需要加分布式锁，否则同一批记录会被多个实例重复推送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandExpireScheduler {

    private final CommandPendingService commandPendingService;

    /** 状态推送（common 域接口，实现由 web 模块提供） */
    private final CommandConfirmNotifier commandConfirmNotifier;

    /**
     * 每轮扫描：把超过确认时限的 PENDING 置为 EXPIRED 并通知前端
     * <p>
     * fixedDelay 而不是 fixedRate：上一轮没跑完就不该开始下一轮，
     * 否则数据库慢的时候会堆积多个扫描线程互相抢同一条记录。
     */
    @Scheduled(fixedDelayString = "${corepulse.command.sweep-interval-ms:60000}")
    public void sweepExpiredCommands() {
        try {
            LocalDateTime cutoff = commandPendingService.timeoutCutoff();
            List<ChatMessage> expired = commandPendingService.expireBatch(cutoff);
            if (expired.isEmpty()) {
                return;
            }
            log.info("超时命令收敛完成: count={}, cutoff={}", expired.size(), cutoff);
            for (ChatMessage message : expired) {
                pushExpired(message);
            }
        } catch (Exception e) {
            log.error("超时命令收敛任务执行失败", e);
        }
    }

    /** 推 EXPIRED 状态：推不出去只记 warn，状态已经在库里，前端回查能自愈 */
    private void pushExpired(ChatMessage message) {
        try {
            commandConfirmNotifier.publishCommandStatus(
                    message.getSessionId(), message.getId(),
                    CommandStatus.EXPIRED.getValue(), "命令已超时未执行", null);
        } catch (Exception e) {
            log.warn("推送超时状态失败: messageId={}, error={}", message.getId(), e.getMessage());
        }
    }
}
