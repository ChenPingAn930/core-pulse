package com.corepulse.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发送聊天消息请求 DTO
 */
@Data
public class ChatSendDTO {

    /** 会话 ID，为空自动新建会话 */
    private Long sessionId;

    /** 用户消息内容 */
    @NotBlank(message = "内容不能为空")
    private String content;
}
