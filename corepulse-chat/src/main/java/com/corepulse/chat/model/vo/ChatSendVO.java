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

    /**
     * 本轮 AI 回复的消息 ID（t_chat_message.id）
     * <p>
     * 后台播报轮没有 HTTP 响应承接，只能靠 WebSocket 推送；带上 ID 后前端才能
     * 按消息去重、断线后补拉，避免同一句回复出现两次或彻底丢失。
     */
    private Long replyMessageId;

    /** 异步任务 ID（触发工具时返回，否则为 null） */
    private Long taskId;
}
