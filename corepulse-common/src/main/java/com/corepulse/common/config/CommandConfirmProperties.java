package com.corepulse.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 命令确认相关配置
 * <p>
 * 为什么放在 common 而不是 chat：超时值有三个消费方——system 模块的 {@code savePending}
 * （判断旧记录是否还能复用）、chat 模块的确认接口与定时扫描。如果各自硬编码一个常量，
 * 三处一旦不一致，就会出现"复用了一条已经过期的待确认记录"这类很难复现的问题。
 */
@Data
@Component
@ConfigurationProperties(prefix = "corepulse.command")
public class CommandConfirmProperties {

    /** 待确认命令的有效期：超过该时长未确认即置为 EXPIRED（默认 5 分钟） */
    private Duration confirmTimeout = Duration.ofMinutes(5);

    /**
     * 过期扫描的间隔（毫秒，默认 60000）。
     * <p>
     * 为什么这里是 long 而不是 Duration：{@code @Scheduled(fixedDelayString=...)} 只认纯数字毫秒或
     * ISO-8601（PT60S），"60s" 这种写法会直接抛 NumberFormatException 导致整个应用起不来。
     * 用毫秒数写进配置，注解和 Java 代码读的是同一个值，不会出现两套时间格式。
     */
    private long sweepIntervalMs = 60_000L;
}
