package com.corepulse.task.executor;

import com.corepulse.domain.enums.TaskStatus;

/**
 * 工具任务执行器: 一个任务类型一个实现(烤机/内存/清理...)
 * 命名 ToolTaskExecutor 避免与 Spring 的 TaskExecutor 冲突。
 */
public interface ToolTaskExecutor {

    /** 任务类型, 与 ToolTask.taskType 对应 */
    String taskType();

    /** 开始执行 */
    void start(Long taskId);

    /** 终止(正常完成/超时/手动停止由 finalStatus 区分) */
    void stop(Long taskId, TaskStatus finalStatus);
}
