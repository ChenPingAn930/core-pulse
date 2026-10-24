package com.corepulse.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 命令执行线程池
 * <p>
 * 为什么不直接在确认接口里同步执行：命令耗时不可控（robocopy、卸载程序可能几十秒），
 * 挂在 HTTP 请求线程上会让"点确认"这个动作一直转圈，前端超时后用户会反复点击。
 * <p>
 * 为什么用独立线程池而不是随手 new Thread：线程数必须可控，否则用户连续确认多条命令就会
 * 无限创建线程；线程名固定成 cmd-exec-N，出问题时能一眼在日志里区分出是命令线程。
 * <p>
 * shutdown 销毁方法：容器关闭时让正在跑的命令有机会结束，而不是直接被杀掉。
 */
@Configuration
public class CommandExecConfig {

    /** 并发执行上限：单机场景下多条命令同时跑会互相抢 CPU/IO，且输出会互相干扰 */
    private static final int POOL_SIZE = 4;

    @Bean(name = "commandExecPool", destroyMethod = "shutdown")
    public ExecutorService commandExecPool() {
        AtomicInteger seq = new AtomicInteger(1);
        return Executors.newFixedThreadPool(POOL_SIZE, runnable -> {
            Thread thread = new Thread(runnable, "cmd-exec-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }
}
