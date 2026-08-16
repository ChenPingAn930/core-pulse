package com.corepulse.task.executor;

import com.corepulse.domain.entity.ToolTask;
import com.corepulse.domain.enums.TaskStatus;
import com.corepulse.task.mapper.ToolTaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 任务执行器（Hermes 执行层）
 * <p>
 * 暂不启用：当前测试纯 LLM 能力，无需 Hermes。如需启用恢复 @Component。
 * <p>
 * 原功能：通过 ProcessBuilder 调用 Hermes 处理复杂任务（清理C盘、卸载软件等）。
 * 同步执行，返回结果；遇到危险操作时任务进入 WAITING_CONFIRM，等待用户确认。
 */
@Deprecated
@Slf4j
@RequiredArgsConstructor
// @Component  // 已禁用 Hermes 执行器，恢复本行即可启用
public class HermesTaskExecutor implements ToolTaskExecutor {

    private final ToolTaskMapper taskMapper;
    private final HermesCommandRunner hermesRunner;
    private final ObjectMapper objectMapper;

    @Override
    public String taskType() {
        return "agent_task";
    }

    @Override
    public void start(Long taskId) {
        execute(taskId);
    }

    /**
     * 同步执行 Hermes 任务，并返回结果文本
     * <p>
     * 供 delegate_to_agent 工具调用，拿到结果后转给 LLM 组织回复。
     *
     * @param taskId 任务 ID
     * @return Hermes 执行结果文本
     */
    public String execute(Long taskId) {
        ToolTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return "任务不存在: " + taskId;
        }

        task.setStatus(TaskStatus.RUNNING.name());
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        // 构建 Hermes 指令：任务描述 + 约束 + 要求输出
        Map<String, Object> params = parseJson(task.getParams());
        String taskDescription = params != null && params.get("taskDescription") != null
                ? String.valueOf(params.get("taskDescription"))
                : "执行电脑维修任务";
        String constraints = params != null && params.get("constraints") != null
                ? String.valueOf(params.get("constraints"))
                : "遇到删除、卸载、重装等危险操作时，先停下来向用户确认，不要擅自执行";

        String query = taskDescription + "。约束：" + constraints
                + "。遇到需要用户确认的危险操作时，明确返回\"需要确认\"字样。";
        String output = hermesRunner.run(query);

        // 解析结果：是否进入确认环节
        if (output.contains("需要确认") || output.contains("待确认") || output.contains("WAITING_CONFIRM")) {
            task.setStatus(TaskStatus.WAITING_CONFIRM.name());
            task.setResult("{\"needsConfirm\":true,\"message\":\"" + escape(output) + "\"}");
            taskMapper.updateById(task);
            log.info("Agent 任务等待确认: taskId={}", taskId);
            return "任务需要用户确认后才能继续。请将以下内容转告用户确认：\n" + output;
        }

        // 正常完成，提取 JSON 结果
        String jsonResult = extractJson(output);
        task.setStatus(TaskStatus.COMPLETED.name());
        task.setResult(jsonResult != null ? jsonResult : "{\"output\":\"" + escape(output) + "\"}");
        task.setProgress(100);
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        log.info("Agent 任务完成: taskId={}", taskId);
        return output;
    }

    @Override
    public void stop(Long taskId, TaskStatus finalStatus) {
        ToolTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(finalStatus.name());
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("Agent 任务结束: taskId={}, status={}", taskId, finalStatus);
    }

    /** 从 Hermes 输出中提取 JSON 块 */
    private String extractJson(String output) {
        if (output == null) {
            return null;
        }
        // 提取 ```json ... ``` 块
        int start = output.indexOf("```json");
        if (start >= 0) {
            start += 7;
            int end = output.indexOf("```", start);
            if (end > start) {
                return output.substring(start, end).trim();
            }
        }
        // 尝试直接解析整段为 JSON
        try {
            objectMapper.readTree(output);
            return output.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return objectMapper.convertValue(node, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
