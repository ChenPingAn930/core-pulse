package com.corepulse.task.executor;

import com.corepulse.common.constant.RedisKeys;
import com.corepulse.common.process.ProcessManager;
import com.corepulse.domain.entity.TaskMetric;
import com.corepulse.domain.entity.ToolTask;
import com.corepulse.domain.enums.TaskStatus;
import com.corepulse.task.event.TaskEventPublisher;
import com.corepulse.task.mapper.TaskMetricMapper;
import com.corepulse.task.mapper.ToolTaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 显卡压力测试执行器 —— M1 为模拟器(随机温度/帧率), M2 接入真实 FurMark 进程托管。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FurMarkTaskExecutor implements ToolTaskExecutor {

    private final ToolTaskMapper taskMapper;
    private final TaskMetricMapper metricMapper;
    private final StringRedisTemplate redisTemplate;
    private final TaskEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<Long, ScheduledFuture<?>> runningTasks = new ConcurrentHashMap<>();
    /** taskId -> sessionId，用于 tick 推送进度时定位会话（避免每秒查库） */
    private final Map<Long, Long> taskSessions = new ConcurrentHashMap<>();

    @Override
    public String taskType() {
        return "furmark";
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

        // 缓存 sessionId，供 tick 推送进度用
        taskSessions.put(taskId, task.getSessionId());

        int durationSec = parseDurationSec(task);
        AtomicInteger elapsed = new AtomicInteger(0);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> tick(taskId, elapsed, durationSec), 0, 1, TimeUnit.SECONDS);
        runningTasks.put(taskId, future);
        log.info("烤机任务启动: taskId={}, durationSec={}", taskId, durationSec);
    }

    private void tick(Long taskId, AtomicInteger elapsed, int durationSec) {
        int sec = elapsed.incrementAndGet();
        double gpuTemp = 67 + ThreadLocalRandom.current().nextDouble() * 8;
        int gpuFps = 85 + ThreadLocalRandom.current().nextInt(15);
        int progress = (int) Math.min(100, sec * 100.0 / durationSec);

        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("progress", progress);
        metric.put("gpuTemp", Math.round(gpuTemp * 10) / 10.0);
        metric.put("gpuFps", gpuFps);
        metric.put("elapsedSec", sec);

        // 进度等实时数据只写 Redis + WebSocket 推送，不落数据库（避免高频写库）
        try {
            redisTemplate.opsForValue().set(RedisKeys.taskMetric(taskId),
                    objectMapper.writeValueAsString(metric));
        } catch (Exception e) {
            log.warn("写 Redis 指标失败: {}", e.getMessage());
        }

        // WebSocket 推送实时进度
        Long sessionId = taskSessions.get(taskId);
        if (sessionId != null) {
            eventPublisher.publishProgress(sessionId, taskId, metric);
        }

        // 每 5 秒采样落库一次历史指标（低频，用于最终报告）
        if (sec % 5 == 0) {
            TaskMetric m = new TaskMetric();
            m.setTaskId(taskId);
            m.setGpuTemp(BigDecimal.valueOf(gpuTemp).setScale(2, RoundingMode.HALF_UP));
            m.setGpuFps(gpuFps);
            m.setRecordTime(LocalDateTime.now());
            metricMapper.insert(m);
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
        // 取出会话缓存（若为空则回退到查库获取）
        Long sessionId = taskSessions.remove(taskId);

        // 终止真实 FurMark 进程（由工具启动并注册到 ProcessManager）
        ProcessManager.kill(taskId);

        ToolTask task = taskMapper.selectById(taskId);
        if (task == null || !TaskStatus.RUNNING.name().equals(task.getStatus())) {
            return;
        }
        if (sessionId == null) {
            sessionId = task.getSessionId();
        }

        // 汇总结果（进度以秒为单位记录运行时长）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("durationSec", 0);
        result.put("conclusion", "显卡烤机已结束。");
        task.setStatus(finalStatus.name());
        task.setResult(toJson(result));
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        redisTemplate.delete(RedisKeys.taskMetric(taskId));
        eventPublisher.publishFinished(sessionId, task);
        log.info("烤机任务结束: taskId={}, status={}", taskId, finalStatus);
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
