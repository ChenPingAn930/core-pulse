package com.corepulse.chat.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageVO {

    /** 消息 ID */
    private Long id;

    /** 消息角色：user / assistant / system / tool */
    private String role;

    /** 消息内容 */
    private String content;

    /** 触发的工具名称（非工具消息为 null） */
    private String toolName;

    /** 关联的异步任务 ID（无则 null） */
    private Long taskId;

    /** 附加信息（如工具调用参数，无则 null） */
    private Object extra;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
