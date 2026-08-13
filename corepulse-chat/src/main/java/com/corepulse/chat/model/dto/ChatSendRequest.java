package com.corepulse.chat.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送消息请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSendRequest {

    /** 会话 ID，为空自动新建会话 */
    private Long sessionId;

    /** 用户消息内容 */
    @NotBlank(message = "消息内容不能为空")
    private String content;
}
