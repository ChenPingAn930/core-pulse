package com.corepulse.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具任务实体（核心状态机）
 * <p>
 * 对应表：t_tool_task。
 * 状态流转：CREATED -> RUNNING -> WAITING_CONFIRM -> RUNNING -> COMPLETED
 *                                \-> CANCELLED / TIMEOUT / FAILED
 */
@Data
@TableName("t_tool_task")
public class ToolTask {

    /** 任务主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务类型：furmark / memtest / clean_disk / full_checkup 等 */
    private String taskType;

    /** 触发任务的会话 ID */
    private Long sessionId;

    /** 任务状态：CREATED / RUNNING / WAITING_CONFIRM / COMPLETED / CANCELLED / TIMEOUT / FAILED */
    private String status;

    /** 任务参数（JSON 字符串） */
    private String params;

    /** 任务执行结果（JSON 字符串） */
    private String result;

    /** 任务进度（0-100） */
    private Integer progress;

    /** 错误信息（失败时才有值） */
    private String errorMsg;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 开始执行时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;
}
