package com.corepulse.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息视图对象
 * <p>
 * 用于向前端返回聊天消息历史。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageVO {

    /** 消息 ID */
    private Long id;

    /** 消息角色：user / assistant / system / tool */
    private String role;

    /** 消息内容 */
    private String content;

    /** 触发的工具名称（工具消息才有值，普通消息为 null） */
    private String toolName;

    /** 关联的异步任务 ID（工具消息才有值，无则 null） */
    private Long taskId;

    /** 附加信息（如工具调用参数等，无则 null） */
    private Object extra;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
