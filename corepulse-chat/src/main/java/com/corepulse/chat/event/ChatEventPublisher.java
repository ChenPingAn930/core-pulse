package com.corepulse.chat.event;

/**
 * 对话过程事件发布(思考中/工具开始/工具结果)。
 * <p>
 * 接口定义在 chat 域, 实现由 web 模块(WebSocket)提供 —— 解耦, 微服务拆分后换 MQ 实现即可。
 * 用于在 AI 对话同步处理期间向前端实时推送阶段信息，避免前端"干等"HTTP 响应。
 */
public interface ChatEventPublisher {

    /**
     * 推送"AI 思考/分析中"事件
     *
     * @param sessionId 会话 ID
     * @param message   中文阶段提示（如"思考中"、"正在分析工具执行结果"）
     */
    void publishThinking(Long sessionId, String message);

    /**
     * 推送"工具开始执行"事件（LLM 已决定调用、即将执行）
     *
     * @param sessionId 会话 ID
     * @param toolName  工具名
     * @param args      工具调用参数摘要（JSON 字符串，可为 null）
     */
    void publishToolStarted(Long sessionId, String toolName, String args);

    /**
     * 推送"工具执行完成"事件
     *
     * @param sessionId 会话 ID
     * @param toolName  工具名
     * @param summary   执行结果摘要（截断后的文本，可为 null）
     */
    void publishToolResult(Long sessionId, String toolName, String summary);

    /**
     * 推送一条"助手回复"
     * <p>
     * 为什么需要它：普通对话的回复通过 HTTP 响应返回给前端，但后台自动触发的推理
     * （如用户确认命令后的结果播报）没有对应的 HTTP 请求，只能走 WebSocket。
     * 不推这条事件，用户就看不到模型说了什么，只能刷新页面重新拉历史。
     *
     * @param sessionId 会话 ID
     * @param content   助手回复正文
     */
    void publishAssistantReply(Long sessionId, String content);
}
