package com.corepulse.domain.enums;

/**
 * 工具任务状态(与 t_tool_task.status 对应, 存字符串)
 */
public enum TaskStatus {
    CREATED,
    RUNNING,
    WAITING_CONFIRM,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMEOUT
}
