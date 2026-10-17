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

    /**
     * 消息来源标记（内部字段，前端不传）
     * <p>
     * command-confirm：由命令确认接口自动生成的"用户已确认"消息。
     * 模型需要看到它才能继续执行，但它并不是用户亲手输入的，
     * 入库时写入 extra.source，前端据此不渲染成用户气泡。
     */
    private String source;
}
