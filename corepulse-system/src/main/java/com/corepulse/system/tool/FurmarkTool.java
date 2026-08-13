package com.corepulse.system.tool;

import com.corepulse.common.process.ProcessManager;
import com.corepulse.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 显卡烤机工具
 * <p>
 * 供 LLM 函数调用：启动真实 FurMark.exe 进行显卡压力测试，
 * 同时通过 task 模块的 MQ 队列实现 30 分钟延时自动关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FurmarkTool implements SystemTool {

    /** FurMark 可执行文件路径（默认指向项目 tool 目录） */
    @Value("${corepulse.tool.furmark-path:./tool/烤鸡工具/FurMark/FurMark.exe}")
    private String furmarkExePath;

    /** 烤机默认时长（分钟） */
    @Value("${corepulse.tool.furmark-default-minutes:30}")
    private int defaultDurationMin;

    private final TaskService taskService;

    @Override
    public String toolName() {
        return "run_furmark";
    }

    @Override
    public String toolDescription() {
        return "启动显卡压力测试（FurMark 烤机），测试显卡稳定性与散热，默认 30 分钟后自动关闭";
    }

    /**
     * 启动显卡烤机测试
     * <p>
     * 流程：启动 FurMark.exe 真实进程 -> 注册到 ProcessManager ->
     * 调用 task 的 startTask 创建任务并下发 MQ（立即执行 + 30 分钟延时终止）。
     * <p>
     * 会话 ID 不暴露给 LLM/用户，由后端通过 ToolContext 注入。
     *
     * @param durationMin 测试时长（分钟），可选，默认 30
     * @param toolContext 工具上下文（后端注入，含 sessionId）
     * @return 执行结果信息（含 taskId）
     */
    @Tool(name = "startFurMark", description = "启动显卡压力测试（FurMark 烤机）。durationMin 为测试分钟数，默认30分钟，最长1440分钟。")
    public String startFurmark(
            @ToolParam(description = "烤机时长，单位分钟，默认30") Integer durationMin,
            ToolContext toolContext) {

        int minutes = (durationMin == null || durationMin <= 0) ? defaultDurationMin : durationMin;
        // 从工具上下文获取会话 ID（由后端注入，LLM 不可见）
        Long sessionId = toolContext != null
                ? (Long) toolContext.getContext().get("sessionId")
                : null;
        File exe = new File(furmarkExePath);

        // 1. 启动真实 FurMark.exe 进程（绕过 start.bat 的 pause）
        Process process = null;
        try {
            if (!exe.exists()) {
                log.error("FurMark 可执行文件不存在: {}", exe.getAbsolutePath());
                return "烤机启动失败：FurMark 工具未找到，路径=" + exe.getAbsolutePath();
            }
            ProcessBuilder pb = new ProcessBuilder(
                    exe.getAbsolutePath(),
                    "/nogui",
                    "/width=1280",
                    "/height=720",
                    "/run_mode=1",
                    "/max_time=" + (minutes * 60_000L));
            pb.directory(exe.getParentFile());
            process = pb.start();
            log.info("FurMark 进程已启动: pid={}, durationMin={}, sessionId={}", process.pid(), minutes, sessionId);
        } catch (Exception e) {
            log.error("启动 FurMark 进程失败", e);
            return "烤机启动失败：" + e.getMessage();
        }

        // 2. 创建任务并下发 MQ（立即执行 + 延时终止）
        Long taskId = null;
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("durationMin", minutes);
            var task = taskService.startTask("furmark", params, sessionId);
            taskId = task.getId();
        } catch (Exception e) {
            log.error("创建烤机任务失败，终止已启动的进程", e);
            process.destroyForcibly();
            return "烤机任务创建失败：" + e.getMessage();
        }

        // 3. 注册进程，供 MQ 延时终止时关闭
        ProcessManager.register(taskId, process);

        return "显卡烤机已启动，测试时长 " + minutes + " 分钟，taskId=" + taskId
                + "。30 分钟后将自动关闭。";
    }
}
