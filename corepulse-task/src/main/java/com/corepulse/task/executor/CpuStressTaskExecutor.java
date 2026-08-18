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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
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
 * CPU 压力测试执行器
 * <p>
 * 通过 PowerShell 启动与 CPU 逻辑核心数相等的满载进程，对 CPU 施加高负载。
 * 每秒读取真实 CPU 占用率（Get-Counter）写入 Redis + WebSocket 推送，
 * 让 LLM 能感知压测是否真正在进行、负载有多高。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CpuStressTaskExecutor implements ToolTaskExecutor {

    private final ToolTaskMapper taskMapper;
    private final StringRedisTemplate redisTemplate;
    private final TaskEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<Long, ScheduledFuture<?>> runningTasks = new ConcurrentHashMap<>();
    /** taskId -> sessionId，用于 tick 推送进度时定位会话 */
    private final Map<Long, Long> taskSessions = new ConcurrentHashMap<>();

    @Override
    public String taskType() {
        return "cpustress";
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

        // 真正启动 CPU 满载进程（按逻辑核心数启动满载 powershell 进程）
        boolean started = startStressProcesses();

        int durationSec = parseDurationSec(task);
        AtomicInteger elapsed = new AtomicInteger(0);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> tick(taskId, elapsed, durationSec), 0, 1, TimeUnit.SECONDS);
        runningTasks.put(taskId, future);
        log.info("CPU 压测任务启动: taskId={}, durationSec={}, 满载进程启动={}", taskId, durationSec, started);
    }

    /** 满载进程唯一标记（用于精确识别并清理，避免误杀其他 powershell） */
    private static final String STRESS_MARKER = "CorePulseCpuStress";

    /**
     * 启动与 CPU 逻辑核心数相等的满载进程
     * <p>
     * 通过 PowerShell 批量启动隐藏窗口的满载进程（每个进程执行带唯一标记的空循环，
     * 占满一个逻辑核），让 CPU 达到高负载。返回是否成功启动。
     */
    private boolean startStressProcesses() {
        try {
            String script = "$cores=(Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors;"
                    + "for($i=0;$i -lt $cores;$i++){"
                    + "Start-Process powershell -ArgumentList '-NoProfile','-WindowStyle','Hidden',"
                    + "'-Command','while($true){} CorePulseCpuStress' };"
                    + "echo $cores";
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy",
                    "Bypass", "-Command", script);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            log.info("已启动 CPU 满载进程，逻辑核心数={}", output);
            return true;
        } catch (Exception e) {
            log.error("启动 CPU 满载进程失败: {}", e.getMessage());
            return false;
        }
    }

    private void tick(Long taskId, AtomicInteger elapsed, int durationSec) {
        int sec = elapsed.incrementAndGet();
        double cpuLoad = readCpuLoad();
        int progress = (int) Math.min(100, sec * 100.0 / durationSec);

        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("progress", progress);
        metric.put("cpuLoad", Math.round(cpuLoad * 10) / 10.0);
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

        // 终止所有 CPU 满载进程
        killStressProcesses();

        ToolTask task = taskMapper.selectById(taskId);
        if (task == null || !TaskStatus.RUNNING.name().equals(task.getStatus())) {
            return;
        }
        if (sessionId == null) {
            sessionId = task.getSessionId();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conclusion", "CPU 压力测试已结束。");
        task.setStatus(finalStatus.name());
        task.setResult(toJson(result));
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        redisTemplate.delete(RedisKeys.taskMetric(taskId));
        eventPublisher.publishFinished(sessionId, task);
        log.info("CPU 压测任务结束: taskId={}, status={}", taskId, finalStatus);
    }

    /**
     * 读取真实 CPU 占用率（百分比 0-100）
     */
    private double readCpuLoad() {
        try {
            String cmd = "(Get-Counter '\\Processor(_Total)\\% Processor Time').CounterSamples.CookedValue";
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy",
                    "Bypass", "-Command", cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null && line.isBlank()) {
                    line = reader.readLine();
                }
                if (line != null) {
                    double v = Double.parseDouble(line.trim());
                    return Math.max(0, Math.min(100, v));
                }
            }
            process.waitFor();
        } catch (Exception e) {
            log.warn("读取 CPU 占用率失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 终止所有 CPU 满载进程
     * <p>
     * 满载进程是通过 PowerShell 批量启动的，无法用单个 Process 句柄管理。
     * 通过唯一标记（STRESS_MARKER）精确识别并清理，避免误杀其他 powershell。
     */
    private void killStressProcesses() {
        try {
            // 先收集匹配标记的进程 PID 列表，再统一终止。
            // 避免在枚举进程集合的同时修改集合（会导致部分进程残留）。
            // 注意：用 Get-CimInstance + Where-Object（单引号）而非 -Filter "..."（双引号），
            // 因为双引号经 ProcessBuilder 传给 powershell -Command 时会丢失，导致语法错误。
            String cmd = "$pids = @(Get-CimInstance Win32_Process | "
                    + "Where-Object { $_.Name -eq 'powershell.exe' -and "
                    + "$_.CommandLine -like '*" + STRESS_MARKER + "*' -and "
                    + "$_.ProcessId -ne $PID } | "
                    + "Select-Object -ExpandProperty ProcessId); "
                    + "$pids | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }; "
                    + "echo \"cleaned=$($pids.Count)\"";
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy",
                    "Bypass", "-Command", cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            log.info("已清理 CPU 满载进程: {}", output);
        } catch (Exception e) {
            log.warn("清理 CPU 满载进程失败: {}", e.getMessage());
        }
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
