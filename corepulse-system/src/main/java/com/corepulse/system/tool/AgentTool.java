package com.corepulse.system.tool;

import com.corepulse.domain.entity.ToolTask;
import com.corepulse.domain.enums.TaskStatus;
import com.corepulse.task.executor.HermesTaskExecutor;
import com.corepulse.task.mapper.ToolTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 委派工具（Hermes）
 * <p>
 * 暂不启用：当前测试纯 LLM（数据工具 + 终端命令 + GUI 工具）能力是否满足需求。
 * 如需启用 Hermes 委派，恢复本类的 @Component 注解即可。
 * <p>
 * 原功能：供 LLM 函数调用，将复杂任务（清理C盘、卸载软件、全面体检等）委派给 Hermes Agent 执行。
 * 同步执行，返回结果给 LLM 组织回复。
 */
@Deprecated
@Slf4j
@RequiredArgsConstructor
// @Component  // 已禁用 Agent 委派，恢复本行即可启用
public class AgentTool implements SystemTool {

    private final HermesTaskExecutor hermesTaskExecutor;
    private final ToolTaskMapper toolTaskMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String toolName() {
        return "delegate_to_agent";
    }

    @Override
    public String toolDescription() {
        return "将复杂任务（清理C盘、卸载软件、全面体检等需要多步骤执行的操作）委派给 AI Agent 执行";
    }

    /**
     * 委派任务给 Agent 执行
     * <p>
     * 同步执行 Hermes，返回结果给 LLM 组织回复。若需要用户确认，任务进入等待确认状态。
     *
     * @param taskDescription 任务描述，如"清理C盘"
     * @param constraints     约束条件，如"不要动用户文档和软件"
     * @param toolContext     工具上下文（含 sessionId）
     * @return 执行结果
     */
    @Tool(name = "delegateToAgent", description = "将复杂电脑维修任务委派给 AI Agent 执行。taskDescription 为任务描述（如清理C盘、卸载某个软件、全面体检），constraints 为约束条件（如不要动用户文档）。")
    public String delegateToAgent(
            @ToolParam(description = "任务描述，如：清理C盘") String taskDescription,
            @ToolParam(description = "约束条件，如：不要动用户文档和软件，可留空") String constraints,
            ToolContext toolContext) {

        Long sessionId = toolContext != null
                ? (Long) toolContext.getContext().get("sessionId")
                : null;

        log.info("调用工具: delegateToAgent，任务描述={}, 约束={}", taskDescription, constraints);

        // 1. 创建 agent_task 任务落库
        ToolTask task = new ToolTask();
        task.setTaskType("agent_task");
        task.setSessionId(sessionId);
        task.setStatus(TaskStatus.CREATED.name());
        task.setProgress(0);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskDescription", taskDescription);
        params.put("constraints", constraints == null ? "" : constraints);
        task.setParams(toJson(params));
        toolTaskMapper.insert(task);
        log.info("调用工具: delegateToAgent，任务已创建 taskId={}", task.getId());

        // 2. 同步执行 Hermes
        String result = hermesTaskExecutor.execute(task.getId());
        log.info("调用工具: delegateToAgent 完成，taskId={}", task.getId());

        // 3. 返回结果（含 taskId，供确认环节使用）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("taskType", "agent_task");
        data.put("result", result);
        return toJson(data);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
