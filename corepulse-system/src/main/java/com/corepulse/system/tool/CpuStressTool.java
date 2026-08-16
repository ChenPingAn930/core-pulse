package com.corepulse.system.tool;

import com.corepulse.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CPU 压力测试工具
 * <p>
 * 通过 PowerShell 启动与 CPU 逻辑核心数相等的满载进程，对 CPU 施加高负载，
 * 纳入任务系统管理（自动超时终止 + 真实 CPU 占用率上报）。
 * <p>
 * 相比打开 Prime95 GUI，本工具让 LLM 能：
 * - 启动压测（startCpuStress）
 * - 查询真实 CPU 占用率判断压测是否在进行（getCpuStressStatus）
 * - 手动停止压测（stopCpuStress）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CpuStressTool implements SystemTool {

    private final TaskService taskService;

    @Override
    public String toolName() {
        return "run_cpu_stress";
    }

    @Override
    public String toolDescription() {
        return "CPU 压力测试：启动与 CPU 核心数相等的满载进程对 CPU 施压，可查询占用率、手动停止，默认 30 分钟自动结束";
    }

    /**
     * 启动 CPU 压力测试
     *
     * @param durationMin 测试时长（分钟），默认 30
     * @param toolContext 工具上下文（后端注入，含 sessionId）
     * @return 执行结果信息（含 taskId）
     */
    @Tool(name = "startCpuStress", description = "启动 CPU 压力测试。durationMin 为测试分钟数，默认30分钟。启动后可通过 getCpuStressStatus 查询真实 CPU 占用率判断压测是否在进行，通过 stopCpuStress 手动停止。")
    public String startCpuStress(
            @ToolParam(description = "压测时长，单位分钟，默认30") Integer durationMin,
            ToolContext toolContext) {

        int minutes = (durationMin == null || durationMin <= 0) ? 30 : durationMin;
        Long sessionId = toolContext != null
                ? (Long) toolContext.getContext().get("sessionId")
                : null;
        log.info("调用工具: startCpuStress，启动 CPU 压测，时长={}分钟", minutes);

        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("durationMin", minutes);
            var task = taskService.startTask("cpustress", params, sessionId);
            log.info("CPU 压测任务已创建: taskId={}", task.getId());
            return "CPU 压力测试已启动，时长 " + minutes + " 分钟，taskId=" + task.getId()
                    + "。可通过 getCpuStressStatus 查询实时 CPU 占用率，或用 stopCpuStress 提前停止。";
        } catch (Exception e) {
            log.error("启动 CPU 压测失败", e);
            return "CPU 压测启动失败：" + e.getMessage();
        }
    }

    /**
     * 查询 CPU 压测实时状态（真实 CPU 占用率）
     *
     * @param taskId 压测任务 ID
     * @return 任务状态 + CPU 占用率 + 进度
     */
    @Tool(name = "getCpuStressStatus", description = "查询 CPU 压测任务的实时状态，返回任务状态、真实 CPU 占用率(%)和进度(%)，用于判断压测是否在进行。")
    public String getCpuStressStatus(
            @ToolParam(description = "压测任务 ID（由 startCpuStress 返回）") Long taskId) {
        log.info("调用工具: getCpuStressStatus，查询 CPU 压测状态，taskId={}", taskId);
        try {
            Map<String, Object> data = taskService.getFurmarkStatus(taskId);
            return "taskId=" + data.get("taskId")
                    + ", status=" + data.get("status")
                    + ", cpuLoad=" + data.get("cpuLoad")
                    + "%, progress=" + data.get("progress")
                    + "%";
        } catch (Exception e) {
            log.error("查询 CPU 压测状态失败", e);
            return "查询失败：" + e.getMessage();
        }
    }

    /**
     * 手动停止 CPU 压测
     * <p>
     * 注意：用户要求停止压测时，<b>必须调用本工具才能真正终止满载进程</b>，
     * 绝不能只回复"已停止"文字而不调用本工具。
     *
     * @param taskId 压测任务 ID
     * @return 停止结果
     */
    @Tool(name = "stopCpuStress", description = "停止 CPU 压测任务（必须调用本工具才能真正终止满载进程）。当用户要求停止压测、关闭压测、结束压测、或取消压测时，必须调用本工具，绝不能只回复文字而不调用。taskId 为 startCpuStress 返回的任务 ID，可从对话历史中获得。停止后本工具会验证满载进程是否真正清理，若未清理干净会明确告知。")
    public String stopCpuStress(
            @ToolParam(description = "压测任务 ID（由 startCpuStress 返回）") Long taskId) {
        log.info("调用工具: stopCpuStress，停止 CPU 压测，taskId={}", taskId);
        if (taskId == null) {
            return "请提供要停止的压测任务 ID（由 startCpuStress 返回）。";
        }
        try {
            taskService.stopTask(taskId);
            // 停止后验证满载进程是否真正清理干净（等待清理命令执行完成）
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            int left = countStressProcesses();
            if (left > 0) {
                log.warn("CPU 压测停止后仍有 {} 个满载进程残留", left);
                return "CPU 压测已停止，taskId=" + taskId + "，但仍有 " + left
                        + " 个满载进程未清理干净！请再次调用 stopCpuStress 重试，或提醒用户手动检查。";
            }
            log.info("CPU 压测停止成功，满载进程已全部清理，taskId={}", taskId);
            return "CPU 压测已停止，taskId=" + taskId + "。已验证满载进程全部清理干净，电脑 CPU 已恢复正常。";
        } catch (Exception e) {
            log.error("停止 CPU 压测失败", e);
            return "停止失败：" + e.getMessage();
        }
    }

    /**
     * 统计当前仍存活的满载进程数量（用于验证清理是否成功）
     */
    private int countStressProcesses() {
        try {
            String cmd = "@(Get-CimInstance Win32_Process | "
                    + "Where-Object { $_.Name -eq 'powershell.exe' -and "
                    + "$_.CommandLine -like '*CorePulseCpuStress*' -and "
                    + "$_.ProcessId -ne $PID }).Count";
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy",
                    "Bypass", "-Command", cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null && line.isBlank()) {
                    line = reader.readLine();
                }
                if (line != null) {
                    return Integer.parseInt(line.trim());
                }
            }
            process.waitFor();
        } catch (Exception e) {
            log.warn("统计满载进程失败: {}", e.getMessage());
        }
        return -1;
    }
}
