package com.corepulse.chat.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送消息响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSendVO {

    /** 会话 ID */
    private Long sessionId;

    /** AI 回复内容 */
    private String reply;

    /** 异步任务 ID（触发工具时返回，否则为 null） */
    private Long taskId;
}
