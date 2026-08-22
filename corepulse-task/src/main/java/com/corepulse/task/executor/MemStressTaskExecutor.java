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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.io.File;
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
 * 通过独立 Java 子进程持续读写内存，目标让系统总内存占用达到约 70%。
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

    /** 内存消耗上限占物理内存总量的比例（70%） */
    @Value("${corepulse.memtest.ratio:0.7}")
    private double memoryRatio;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<Long, ScheduledFuture<?>> runningTasks = new ConcurrentHashMap<>();
    private final Map<Long, Long> taskSessions = new ConcurrentHashMap<>();
    private final Map<Long, Process> memoryStressProcesses = new ConcurrentHashMap<>();

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

        // 使用独立 JVM 分配并持续读写内存，避免受 Web 应用自身堆限制
        boolean started = startJavaMemoryStress(taskId);

        int durationSec = parseDurationSec(task);
        AtomicInteger elapsed = new AtomicInteger(0);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> tick(taskId, elapsed, durationSec), 0, 1, TimeUnit.SECONDS);
        runningTasks.put(taskId, future);
        log.info("内存压测任务启动: taskId={}, durationSec={}, Java内存压测启动={}", taskId, durationSec, started);
    }

    private void tick(Long taskId, AtomicInteger elapsed, int durationSec) {
        int sec = elapsed.incrementAndGet();
        Process process = memoryStressProcesses.get(taskId);
        if (process == null || !process.isAlive()) {
            log.error("内存压测子进程提前退出: taskId={}", taskId);
            stop(taskId, TaskStatus.FAILED);
            return;
        }
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

        stopJavaMemoryStress(taskId);

        ToolTask task = taskMapper.selectById(taskId);
        if (task == null || !TaskStatus.RUNNING.name().equals(task.getStatus())) {
            return;
        }
        if (sessionId == null) {
            sessionId = task.getSessionId();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion", "内存压力测试已结束，独立 Java 压测进程已停止，内存已释放。");
        task.setStatus(finalStatus.name());
        task.setResult(toJson(result));
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        redisTemplate.delete(RedisKeys.taskMetric(taskId));
        eventPublisher.publishFinished(sessionId, task);
        log.info("内存压测任务结束: taskId={}, status={}", taskId, finalStatus);
    }

    /**
     * 启动独立 JVM 内存压测进程。子进程使用自身堆，不受 Web 应用 -Xmx 限制。
     */
    private boolean startJavaMemoryStress(Long taskId) {
        long physicalMb = readTotalMemoryMb();
        long currentUsedMb = Math.round(physicalMb * readMemUsage() / 100.0);
        long desiredUsedMb = Math.round(physicalMb * memoryRatio);
        long targetMb = Math.max(256, desiredUsedMb - currentUsedMb);
        long heapMb = targetMb + Math.max(1024, Math.round(targetMb * 0.1));
        try {
            String javaCommand = new File(System.getProperty("java.home"), "bin/java.exe").getAbsolutePath();
            String classpath = System.getProperty("java.class.path");
            ProcessBuilder builder = new ProcessBuilder(
                    javaCommand,
                    "-Xms" + Math.min(512, heapMb) + "m",
                    "-Xmx" + heapMb + "m",
                    "-cp", classpath,
                    MemoryStressWorker.class.getName(),
                    String.valueOf(targetMb));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            Thread outputReader = new Thread(() -> readWorkerOutput(taskId, process),
                    "corepulse-memstress-output-" + taskId);
            outputReader.setDaemon(true);
            outputReader.start();
            memoryStressProcesses.put(taskId, process);
            log.info("Java 独立内存压测已启动: taskId={}, 物理内存={}MB, 当前已用={}MB, 目标新增={}MB, 子进程堆={}MB, pid={}",
                    taskId, physicalMb, currentUsedMb, targetMb, heapMb, process.pid());
            return true;
        } catch (Exception e) {
            log.error("启动 Java 独立内存压测失败", e);
            return false;
        }
    }

    private void readWorkerOutput(Long taskId, Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.warn("内存压测子进程输出: taskId={}, {}", taskId, line);
            }
        } catch (Exception e) {
            log.debug("读取内存压测子进程输出结束: taskId={}, {}", taskId, e.getMessage());
        }
    }

    /** 停止子进程并释放其全部内存。 */
    private void stopJavaMemoryStress(Long taskId) {
        Process process = memoryStressProcesses.remove(taskId);
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS) && process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Java 独立内存压测进程已清理: taskId={}", taskId);
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
