package com.corepulse.common.event;

/**
 * 命令确认事件推送器
 * <p>
 * 定义在 common 域而不是 chat 域：调用方是 corepulse-system 的 ShellCommandTool，
 * 而 system 模块不能反向依赖 chat（chat 已经依赖 system），否则会形成模块循环依赖。
 * 接口只依赖 JDK 类型，落地实现由 web 模块（WebSocket）提供。
 */
public interface CommandConfirmNotifier {

    /**
     * 推送"有一条命令等待用户确认"事件
     * <p>
     * 命令记录此时已经独立提交，前端收到事件后可以立即渲染确认卡片并调用确认接口。
     *
     * @param sessionId 会话 ID
     * @param messageId 待确认命令的消息 ID（t_chat_message.id）
     * @param command   命令原文
     */
    void publishCommandConfirm(Long sessionId, Long messageId, String command);
}
