package com.corepulse.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.corepulse.common.constant.RedisKeys;
import com.corepulse.common.exception.BizException;
import com.corepulse.common.process.ProcessManager;
import com.corepulse.common.result.ResultCode;
import com.corepulse.domain.entity.ToolTask;
import com.corepulse.domain.enums.TaskStatus;
import com.corepulse.domain.vo.TaskVO;
import com.corepulse.task.executor.ToolTaskExecutor;
import com.corepulse.task.mapper.ToolTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 任务服务: 创建/执行/查询/确认 —— 任务状态机核心
 * <p>
 * 使用 JVM 内置的 ScheduledExecutorService 实现延时终止，
 * 不依赖外部 RabbitMQ，适合直接运行在用户本机的场景。
 */
@Slf4j
@Service
public class TaskService {

    /** 各任务类型的执行器（taskType -> executor） */
    private final Map<String, ToolTaskExecutor> executorMap;
    private final ToolTaskMapper taskMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** JVM 内置延时调度器：用于任务超时自动终止 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    /** 已调度的延时终止任务（taskId -> future），用于取消 */
    private final Map<Long, ScheduledFuture<?>> timeoutTasks = new java.util.concurrent.ConcurrentHashMap<>();

    public TaskService(List<ToolTaskExecutor> executors,
                       ToolTaskMapper taskMapper,
                       StringRedisTemplate redisTemplate,
                       ObjectMapper objectMapper) {
        Map<String, ToolTaskExecutor> map = new HashMap<>();
        for (ToolTaskExecutor executor : executors) {
            map.put(executor.taskType(), executor);
        }
        this.executorMap = map;
        this.taskMapper = taskMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        log.info("任务执行器已注册: {}", executorMap.keySet());
    }

    /** 创建任务并执行(立即执行 + JVM 延时自动终止) */
    public ToolTask startTask(String taskType, Map<String, Object> params, Long sessionId) {
        // 检查是否存在"进行中"的任务（CREATED / RUNNING）
        // 关键：不仅要看数据库状态，还要验证对应进程是否真的存活
        List<ToolTask> runningTasks = taskMapper.selectList(new LambdaQueryWrapper<ToolTask>()
                .eq(ToolTask::getTaskType, taskType)
                .in(ToolTask::getStatus, List.of(TaskStatus.CREATED.name(), TaskStatus.RUNNING.name())));

        // 区分任务类型：仅"后台持续进程"类任务（如 furmark）做进程存活检测，
        // agent_task 等同步执行的任务不做（其进程生命周期与后台进程不同）
        boolean supportsProcessCheck = ProcessManager.isSupported(taskType);

        for (ToolTask runningTask : runningTasks) {
            // 支持进程检测的任务：若进程仍存活（用系统命令复核），说明任务真的在跑，拒绝
            if (supportsProcessCheck && ProcessManager.isTaskRunning(runningTask.getId())) {
                throw new BizException(ResultCode.TASK_STATE_ERROR,
                        "已有进行中的 " + taskType + " 任务(任务ID=" + runningTask.getId() + "), 请先停止");
            }
            // 不支持进程检测，或进程已不存在(被手动关闭/崩溃)，说明是残留状态，标记为 FAILED
            log.warn("检测到 {} 任务(taskId={}) 状态为 {}，进程检测={}，视为残留并标记为 FAILED",
                    taskType, runningTask.getId(), runningTask.getStatus(), supportsProcessCheck);
            runningTask.setStatus(TaskStatus.FAILED.name());
            runningTask.setErrorMsg("任务异常中断（进程不存在或被手动关闭）");
            runningTask.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(runningTask);
        }

        // 校验执行器存在
        ToolTaskExecutor executor = executorMap.get(taskType);
        if (executor == null) {
            throw new BizException(ResultCode.BAD_REQUEST, "无执行器处理任务类型: " + taskType);
        }

        ToolTask task = new ToolTask();
        task.setTaskType(taskType);
        task.setSessionId(sessionId);
        task.setStatus(TaskStatus.CREATED.name());
        task.setProgress(0);
        task.setParams(toJson(params));
        taskMapper.insert(task);

        // 立即执行
        try {
            executor.start(task.getId());
        } catch (Exception e) {
            log.error("任务启动失败: taskId={}", task.getId(), e);
            task.setStatus(TaskStatus.FAILED.name());
            task.setErrorMsg(e.getMessage());
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            throw new BizException(ResultCode.TOOL_ERROR, "任务启动失败: " + e.getMessage());
        }

        // JVM 延时自动终止（替代 RabbitMQ 死信队列）
        int durationMin = params.get("durationMin") instanceof Number n ? n.intValue() : 30;
        Long taskId = task.getId();
        ScheduledFuture<?> future = scheduler.schedule(
                () -> {
                    log.info("任务超时自动终止: taskId={}, type={}", taskId, taskType);
                    executor.stop(taskId, TaskStatus.TIMEOUT);
                    timeoutTasks.remove(taskId);
                },
                durationMin, TimeUnit.MINUTES);
        timeoutTasks.put(taskId, future);

        log.info("任务已创建并执行: taskId={}, type={}, durationMin={}", taskId, taskType, durationMin);
        return task;
    }

    /** 手动停止 */
    public void stopTask(Long taskId) {
        ToolTask task = getTaskOrThrow(taskId);
        if (!List.of(TaskStatus.CREATED.name(), TaskStatus.RUNNING.name()).contains(task.getStatus())) {
            throw new BizException(ResultCode.TASK_STATE_ERROR, "任务已结束, 无法停止");
        }
        // 取消已调度的延时终止
        ScheduledFuture<?> future = timeoutTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
        ToolTaskExecutor executor = executorMap.get(task.getTaskType());
        if (executor != null) {
            executor.stop(taskId, TaskStatus.CANCELLED);
        }
    }

    public ToolTask getTaskOrThrow(Long taskId) {
        ToolTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ResultCode.NOT_FOUND, "任务不存在: " + taskId);
        }
        return task;
    }

    public TaskVO getTaskVO(Long taskId) {
        ToolTask task = getTaskOrThrow(taskId);
        TaskVO vo = new TaskVO();
        vo.setTaskId(task.getId());
        vo.setTaskType(task.getTaskType());
        vo.setStatus(task.getStatus());
        vo.setProgress(task.getProgress());
        vo.setParams(parseJson(task.getParams()));
        vo.setResult(parseJson(task.getResult()));
        vo.setCreatedAt(task.getCreatedAt());
        vo.setFinishedAt(task.getFinishedAt());
        return vo;
    }

    /** 烤机状态(DB 状态 + Redis 实时指标) */
    public Map<String, Object> getFurmarkStatus(Long taskId) {
        ToolTask task = getTaskOrThrow(taskId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("status", task.getStatus());
        data.put("progress", task.getProgress());

        String metric = redisTemplate.opsForValue().get(RedisKeys.taskMetric(taskId));
        if (metric != null) {
            try {
                Map<String, Object> m = objectMapper.readValue(metric, Map.class);
                data.putAll(m);
            } catch (Exception ignored) {
            }
        }
        data.put("startedAt", task.getStartedAt());
        data.put("finishedAt", task.getFinishedAt());
        data.put("result", parseJson(task.getResult()));
        return data;
    }

    /** 确认/拒绝破坏性操作(M3 完整启用) */
    public void confirm(Long taskId, boolean approved) {
        ToolTask task = getTaskOrThrow(taskId);
        if (!TaskStatus.WAITING_CONFIRM.name().equals(task.getStatus())) {
            throw new BizException(ResultCode.TASK_STATE_ERROR, "任务不在待确认状态");
        }
        if (approved) {
            task.setStatus(TaskStatus.RUNNING.name());
            taskMapper.updateById(task);
            // 确认后重启执行器（预留 M3 实际执行逻辑）
            ToolTaskExecutor executor = executorMap.get(task.getTaskType());
            if (executor != null) {
                executor.start(taskId);
            }
        } else {
            task.setStatus(TaskStatus.CANCELLED.name());
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
