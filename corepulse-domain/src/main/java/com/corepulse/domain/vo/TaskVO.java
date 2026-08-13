package com.corepulse.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务视图对象
 * <p>
 * 用于向前端返回任务的状态与结果。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskVO {

    /** 任务 ID */
    private Long taskId;

    /** 任务类型：furmark / memtest / clean_disk / full_checkup 等 */
    private String taskType;

    /** 任务状态：CREATED / RUNNING / WAITING_CONFIRM / COMPLETED / CANCELLED / TIMEOUT / FAILED */
    private String status;

    /** 任务进度（0-100） */
    private Integer progress;

    /** 任务参数 */
    private Object params;

    /** 任务结果 */
    private Object result;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;
}
