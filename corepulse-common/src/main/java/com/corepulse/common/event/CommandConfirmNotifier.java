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

    /**
     * 推送"命令状态变化"事件（执行中 / 执行成功 / 执行失败）
     * <p>
     * 为什么需要这个事件：确认之后前端只能等一次同步 HTTP（内含整轮 LLM 调用），
     * 期间界面没有任何信息来源，看起来就像后端卡死。状态变更由工具侧产生，
     * 就该由工具侧广播，前端只做展示。
     * <p>
     * 约定：调用前状态必须已经落库提交，前端收到事件后立即回查也能读到一致结果。
     *
     * @param sessionId 会话 ID
     * @param messageId 命令记录的消息 ID（t_chat_message.id）
     * @param status    状态值（CommandStatus 的 value：EXECUTING / EXECUTED / FAILED）
     * @param summary   结果摘要（失败时为错误原因，可直接展示给用户）
     * @param costMs    执行耗时（毫秒），未知时传 null
     */
    void publishCommandStatus(Long sessionId, Long messageId, String status, String summary, Long costMs);
}
