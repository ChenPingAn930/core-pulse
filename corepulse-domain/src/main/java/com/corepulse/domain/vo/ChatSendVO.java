package com.corepulse.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 发送消息响应视图对象
 * <p>
 * 用于向前端返回 AI 对话的回复结果。
 */
@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatSendVO {

    /** 会话 ID */
    private Long sessionId;

    /** AI 回复内容 */
    private String reply;

    /** 触发异步工具时返回的任务 ID，未触发则为 null */
    private Long taskId;
}
