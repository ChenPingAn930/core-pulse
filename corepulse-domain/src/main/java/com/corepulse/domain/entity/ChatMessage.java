package com.corepulse.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息实体
 * <p>
 * 对应表：t_chat_message，保存一次对话中的单条消息。
 */
@Data
@TableName("t_chat_message")
public class ChatMessage {

    /** 消息主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID */
    private Long sessionId;

    /** 消息角色：user / assistant / system / tool */
    private String role;

    /** 消息内容 */
    private String content;

    /** 触发的工具名称（工具消息才有值，普通消息为 null） */
    private String toolName;

    /** 关联的异步任务 ID（工具消息才有值，无则 null） */
    private Long taskId;

    /** 附加信息（如工具调用参数等，无则 null） */
    private String extra;

    /** 是否已确认（0-未确认，1-已确认），仅命令类记录使用 */
    private Integer confirmed;

    /**
     * 命令确认状态：PENDING / CONFIRMED / EXECUTED（见 CommandStatus）
     * <p>普通消息为 null；命令类工具记录必须有值，用于区分
     * "待确认"、"已确认未执行"、"已执行"，避免同一条命令被执行多次。
     */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
