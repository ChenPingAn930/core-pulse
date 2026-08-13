package com.corepulse.task.mq;

import com.corepulse.common.constant.MqConstants;
import com.corepulse.domain.enums.TaskStatus;
import com.corepulse.task.executor.ToolTaskExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务消费者(桌面执行端): 收到 EXECUTE 启动, 收到 TIMEOUT/STOP 终止
 */
@Slf4j
@Component
public class TaskConsumer {

    private final Map<String, ToolTaskExecutor> executorMap;

    public TaskConsumer(List<ToolTaskExecutor> executors) {
        Map<String, ToolTaskExecutor> map = new HashMap<>();
        for (ToolTaskExecutor executor : executors) {
            map.put(executor.taskType(), executor);
        }
        this.executorMap = map;
        log.info("任务执行器已注册: {}", executorMap.keySet());
    }

    @RabbitListener(queues = MqConstants.QUEUE_EXECUTE)
    public void onMessage(TaskMessage msg) {
        log.info("收到任务消息: taskId={}, type={}, msgType={}", msg.getTaskId(), msg.getTaskType(), msg.getMsgType());
        if (msg == null || msg.getTaskId() == null) {
            return;
        }
        ToolTaskExecutor executor = executorMap.get(msg.getTaskType());
        if (executor == null) {
            log.warn("无执行器处理任务类型: {}", msg.getTaskType());
            return;
        }
        switch (msg.getMsgType()) {
            case MqConstants.MSG_TYPE_EXECUTE -> executor.start(msg.getTaskId());
            case MqConstants.MSG_TYPE_TIMEOUT -> executor.stop(msg.getTaskId(), TaskStatus.TIMEOUT);
            case MqConstants.MSG_TYPE_STOP -> executor.stop(msg.getTaskId(), TaskStatus.CANCELLED);
            case MqConstants.MSG_TYPE_CONFIRM -> log.info("确认后继续(预留 M3): taskId={}", msg.getTaskId());
            default -> log.warn("未知消息类型: {}", msg.getMsgType());
        }
    }
}
