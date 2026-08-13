package com.corepulse.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.corepulse.common.constant.MqConstants;
import com.corepulse.common.constant.RedisKeys;
import com.corepulse.common.exception.BizException;
import com.corepulse.common.result.ResultCode;
import com.corepulse.domain.entity.ToolTask;
import com.corepulse.domain.enums.TaskStatus;
import com.corepulse.domain.vo.TaskVO;
import com.corepulse.task.mapper.ToolTaskMapper;
import com.corepulse.task.mq.TaskMessage;
import com.corepulse.task.mq.TaskProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务服务: 创建/下发/查询/确认 —— 任务状态机核心
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final ToolTaskMapper taskMapper;
    private final TaskProducer producer;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 创建任务并下发 MQ(立即执行 + 延时终止两条消息) */
    public ToolTask startTask(String taskType, Map<String, Object> params, Long sessionId) {
        Long running = taskMapper.selectCount(new LambdaQueryWrapper<ToolTask>()
                .eq(ToolTask::getTaskType, taskType)
                .in(ToolTask::getStatus, List.of(TaskStatus.CREATED.name(), TaskStatus.RUNNING.name())));
        if (running > 0) {
            throw new BizException(ResultCode.TASK_STATE_ERROR, "已有进行中的 " + taskType + " 任务, 请先停止");
        }

        ToolTask task = new ToolTask();
        task.setTaskType(taskType);
        task.setSessionId(sessionId);
        task.setStatus(TaskStatus.CREATED.name());
        task.setProgress(0);
        task.setParams(toJson(params));
        taskMapper.insert(task);

        TaskMessage execute = new TaskMessage();
        execute.setTaskId(task.getId());
        execute.setMsgType(MqConstants.MSG_TYPE_EXECUTE);
        execute.setTaskType(taskType);
        execute.setSessionId(sessionId);
        execute.setParams(params);
        producer.sendExecute(execute);

        // 延时终止(死信队列)
        int durationMin = params.get("durationMin") instanceof Number n ? n.intValue() : 30;
        TaskMessage timeout = new TaskMessage();
        timeout.setTaskId(task.getId());
        timeout.setMsgType(MqConstants.MSG_TYPE_TIMEOUT);
        timeout.setTaskType(taskType);
        timeout.setSessionId(sessionId);
        producer.sendDelayTimeout(timeout, durationMin * 60_000L);

        log.info("任务已创建并下发: taskId={}, type={}, durationMin={}", task.getId(), taskType, durationMin);
        return task;
    }

    /** 手动停止 */
    public void stopTask(Long taskId) {
        ToolTask task = getTaskOrThrow(taskId);
        if (!List.of(TaskStatus.CREATED.name(), TaskStatus.RUNNING.name()).contains(task.getStatus())) {
            throw new BizException(ResultCode.TASK_STATE_ERROR, "任务已结束, 无法停止");
        }
        TaskMessage stop = new TaskMessage();
        stop.setTaskId(taskId);
        stop.setTaskType(task.getTaskType());
        stop.setSessionId(task.getSessionId());
        producer.sendStop(stop);
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
            TaskMessage confirm = new TaskMessage();
            confirm.setTaskId(taskId);
            confirm.setMsgType(MqConstants.MSG_TYPE_CONFIRM);
            confirm.setTaskType(task.getTaskType());
            confirm.setSessionId(task.getSessionId());
            producer.sendExecute(confirm);
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
