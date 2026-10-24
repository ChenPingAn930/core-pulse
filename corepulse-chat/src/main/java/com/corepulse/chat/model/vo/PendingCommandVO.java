package com.corepulse.chat.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待确认命令卡片数据
 * <p>
 * 用途：卡片以数据库为唯一事实来源。WebSocket 事件只是"加速通道"，
 * 页面刷新、断线重连、推送失败时，前端靠这个接口把漏掉的卡片补齐，
 * 避免出现"对话里说需要确认、界面上却无卡片可点"的错位。
 * <p>
 * 为什么不返回命令输出：待确认命令还没执行，本来就没有输出。
 */
@Data
@Builder
public class PendingCommandVO {

    /** 命令记录的消息 ID（用户点确认时回传给确认接口） */
    private Long messageId;

    /** 命令原文：卡片上要原样展示，用户确认的就是这条命令 */
    private String command;

    /** 当前状态，正常情况恒为 PENDING；保留字段便于前端统一处理 */
    private String status;

    /** 记录创建时间，前端据此显示已等待多久 */
    private LocalDateTime createdAt;

    /** 失效时间：超过这个时刻未确认，后端会把它置为 EXPIRED，前端可直接据此倒计时 */
    private LocalDateTime expiresAt;
}
