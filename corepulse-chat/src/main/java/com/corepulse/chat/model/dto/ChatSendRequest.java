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

    /**
     * 来源标记：命令执行结果播报（由后端在命令执行完后自动触发）
     * <p>
     * 放在 DTO 上做唯一来源：触发方（CommandResultNoticeService）与判定方（ChatServiceImpl）
     * 引用同一个常量，避免一处写 "command-result"、另一处写成别的值导致豁免失效。
     */
    public static final String SOURCE_COMMAND_RESULT = "command-result";

    /** 会话 ID，为空自动新建会话 */
    private Long sessionId;

    /** 用户消息内容 */
    @NotBlank(message = "消息内容不能为空")
    private String content;

    /**
     * 消息来源标记（内部字段，前端不传）
     * <p>
     * command-result：命令结果播报轮次，由后端自动触发。
     * 模型需要看到它才能继续执行，但它并不是用户亲手输入的，
     * 入库时写入 extra.source，前端据此不渲染成用户气泡。
     * <p>
     * 注意：该字段决定"是否豁免卸载校验/卡片仲裁"，属于后端内部信任标记，
     * 控制层会强制清空外部请求传入的值（见 ChatController）。
     */
    private String source;
}
