package com.corepulse.web.websocket;

import com.corepulse.domain.entity.ToolTask;
import com.corepulse.task.event.TaskEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务事件 -> WebSocket 推送实现(task 域接口的落地实现)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketTaskEventPublisher implements TaskEventPublisher {

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    @Override
    public void publishProgress(Long sessionId, Long taskId, Object metric) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "tool_progress");
        event.put("taskId", taskId);
        event.putAll(objectMapper.convertValue(metric, Map.class));
        sessionManager.send(sessionId, event);
    }

    @Override
    public void publishFinished(Long sessionId, ToolTask task) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "tool_finished");
        event.put("taskId", task.getId());
        event.put("status", task.getStatus());
        event.put("result", parseResult(task.getResult()));
        sessionManager.send(sessionId, event);
    }

    @Override
    public void publishConfirmRequest(Long sessionId, Long taskId, String message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "confirm_request");
        event.put("taskId", taskId);
        event.put("message", message);
        sessionManager.send(sessionId, event);
    }

    private Object parseResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
