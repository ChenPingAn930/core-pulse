package com.corepulse.task.executor;

import com.corepulse.common.constant.RedisKeys;
import com.corepulse.domain.entity.ToolTask;
import com.corepulse.domain.enums.TaskStatus;
import com.corepulse.task.event.TaskEventPublisher;
import com.corepulse.task.mapper.ToolTaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存压力测试执行器
 * <p>
 * 通过 Sysinternals Testlimit（-d 参数真正占用物理内存）对内存施加压力，
 * 消耗量为物理内存总量的 70%（保留 30% 给系统，避免死机）。
 * 每秒读取真实内存占用率（wmic）写入 Redis + WebSocket 推送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemStressTaskExecutor implements ToolTaskExecutor {

    private final ToolTaskMapper taskMapper;
    private final StringRedisTemplate redisTemplate;
    private final TaskEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** Testlimit 可执行文件路径（由配置注入，默认指向项目 tool 目录） */
    @Value("${corepulse.tool.testlimit-path:./tool/内存工具/Testlimit/Testlimit64.exe}")
    private String testlimitPath;

    /** 内存消耗上限占物理内存总量的比例（70%） */
    @Value("${corepulse.memtest.ratio:0.7}")
    private double memoryRatio;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<Long, ScheduledFuture<?>> runningTasks = new ConcurrentHashMap<>();
    private final Map<Long, Long> taskSessions = new ConcurrentHashMap<>();

    @Override
    public String taskType() {
        return "memstress";
    }

    @Override
    public void start(Long taskId) {
        ToolTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(TaskStatus.RUNNING.name());
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        taskSessions.put(taskId, task.getSessionId());

        // 启动 Testlimit 消耗内存
        boolean started = startTestlimit();

        int durationSec = parseDurationSec(task);
        AtomicInteger elapsed = new AtomicInteger(0);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> tick(taskId, elapsed, durationSec), 0, 1, TimeUnit.SECONDS);
        runningTasks.put(taskId, future);
        log.info("内存压测任务启动: taskId={}, durationSec={}, Testlimit启动={}", taskId, durationSec, started);
    }

    private void tick(Long taskId, AtomicInteger elapsed, int durationSec) {
        int sec = elapsed.incrementAndGet();
        double memLoad = readMemUsage();
        int progress = (int) Math.min(100, sec * 100.0 / durationSec);

        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("progress", progress);
        metric.put("memUsage", Math.round(memLoad * 10) / 10.0);
        metric.put("elapsedSec", sec);

        try {
            redisTemplate.opsForValue().set(RedisKeys.taskMetric(taskId),
                    objectMapper.writeValueAsString(metric));
        } catch (Exception e) {
            log.warn("写 Redis 指标失败: {}", e.getMessage());
        }

        Long sessionId = taskSessions.get(taskId);
        if (sessionId != null) {
            eventPublisher.publishProgress(sessionId, taskId, metric);
        }

        if (progress >= 100) {
            stop(taskId, TaskStatus.COMPLETED);
        }
    }

    @Override
    public void stop(Long taskId, TaskStatus finalStatus) {
        ScheduledFuture<?> future = runningTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
        Long sessionId = taskSessions.remove(taskId);

        killTestlimit();

        ToolTask task = taskMapper.selectById(taskId);
        if (task == null || !TaskStatus.RUNNING.name().equals(task.getStatus())) {
            return;
        }
        if (sessionId == null) {
            sessionId = task.getSessionId();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion", "内存压力测试已结束，Testlimit 进程已清理，内存已释放。");
        task.setStatus(finalStatus.name());
        task.setResult(toJson(result));
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        redisTemplate.delete(RedisKeys.taskMetric(taskId));
        eventPublisher.publishFinished(sessionId, task);
        log.info("内存压测任务结束: taskId={}, status={}", taskId, finalStatus);
    }

    /**
     * 启动 Testlimit 消耗物理内存总量的 70%
     * <p>
     * 使用 -d 参数（泄漏并触摸内存，真正占用物理内存），-c 1 限制只分配一次，
     * 避免 Testlimit 默认"尽可能多"导致内存耗尽。
     */
    private boolean startTestlimit() {
        try {
            long totalMb = readTotalMemoryMb();
            long toAllocateMb = Math.max(1, Math.round(totalMb * memoryRatio));
            File exe = new File(testlimitPath);
            if (!exe.exists()) {
                log.error("Testlimit 不存在: {}", testlimitPath);
                return false;
            }
            ProcessBuilder pb = new ProcessBuilder(
                    exe.getAbsolutePath(),
                    "-accepteula", "-d", String.valueOf(toAllocateMb), "-c", "1");
            pb.directory(exe.getParentFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            log.info("Testlimit 已启动: 总内存={}MB, 消耗={}MB, pid={}", totalMb, toAllocateMb, process.pid());
            return true;
        } catch (Exception e) {
            log.error("启动 Testlimit 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 终止 Testlimit 进程，释放内存
     */
    private void killTestlimit() {
        try {
            String cmd = "Stop-Process -Name Testlimit64 -Force -ErrorAction SilentlyContinue";
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy",
                    "Bypass", "-Command", cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor();
            log.info("Testlimit 进程已清理");
        } catch (Exception e) {
            log.warn("清理 Testlimit 进程失败: {}", e.getMessage());
        }
    }

    /**
     * 读取物理内存总量（MB）
     */
    private long readTotalMemoryMb() {
        try {
            ProcessBuilder pb = new ProcessBuilder("wmic", "OS", "get", "TotalVisibleMemorySize", "/format:value");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().startsWith("TotalVisibleMemorySize=")) {
                        return Long.parseLong(line.trim().split("=")[1].trim()) / 1024;
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            log.warn("读取内存总量失败: {}", e.getMessage());
        }
        return 16384;
    }

    /**
     * 读取真实内存使用率（百分比 0-100）
     */
    private double readMemUsage() {
        try {
            ProcessBuilder pb = new ProcessBuilder("wmic", "OS", "get", "FreePhysicalMemory,TotalVisibleMemorySize", "/format:value");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            long free = -1;
            long total = -1;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String t = line.trim();
                    if (t.startsWith("FreePhysicalMemory=")) {
                        free = Long.parseLong(t.split("=")[1].trim());
                    } else if (t.startsWith("TotalVisibleMemorySize=")) {
                        total = Long.parseLong(t.split("=")[1].trim());
                    }
                }
            }
            process.waitFor();
            if (total > 0 && free >= 0) {
                double usage = (total - free) * 100.0 / total;
                return Math.max(0, Math.min(100, usage));
            }
        } catch (Exception e) {
            log.warn("读取内存占用率失败: {}", e.getMessage());
        }
        return 0;
    }

    private int parseDurationSec(ToolTask task) {
        try {
            JsonNode params = objectMapper.readTree(task.getParams());
            return params.path("durationMin").asInt(30) * 60;
        } catch (Exception e) {
            return 30 * 60;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
