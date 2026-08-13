package com.corepulse.task.mq;

import lombok.Data;

import java.util.Map;

/**
 * MQ 任务消息(JSON)
 * msgType: EXECUTE 立即执行 / TIMEOUT 延时到期终止 / STOP 手动停止 / CONFIRM 确认后继续
 */
@Data
public class TaskMessage {

    private Long taskId;
    private String msgType;
    private String taskType;
    private Long sessionId;
    private Map<String, Object> params;
}
