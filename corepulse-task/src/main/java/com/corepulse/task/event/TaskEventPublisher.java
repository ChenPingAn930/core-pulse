package com.corepulse.task.event;

import com.corepulse.domain.entity.ToolTask;

/**
 * 任务事件发布(进度/完成/需确认)。
 * 接口定义在 task 域, 实现由 web 模块(WebSocket)提供 —— 解耦, 微服务拆分后换 MQ 实现即可。
 */
public interface TaskEventPublisher {

    void publishProgress(Long sessionId, Long taskId, Object metric);

    void publishFinished(Long sessionId, ToolTask task);

    void publishConfirmRequest(Long sessionId, Long taskId, String message);
}
