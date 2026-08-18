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
 * 内存压力测试工具
 * <p>
 * 通过 Sysinternals Testlimit（-d 参数真正占用物理内存）对内存施加压力，
 * 消耗物理内存总量的 70%（保留 30% 给系统），纳入任务系统管理。
 * <p>
 * 安全策略（重要）：
 * 内存压测是<b>高危操作</b>（高内存占用可能导致系统卡顿、程序无响应），
 * 必须用户<b>明确同意</b>后才真正启动。startMemStress 首次调用需返回确认请求，
 * 用户确认后（confirmed=true）才执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemStressTool implements SystemTool {

    private final TaskService taskService;

    @Override
    public String toolName() {
        return "run_mem_stress";
    }

    @Override
    public String toolDescription() {
        return "内存压力测试：用 Testlimit 消耗物理内存的70%进行压测，高危操作必须用户确认后才启动，可查询占用率、手动停止，默认30分钟";
    }

    /**
     * 启动内存压力测试（高危操作，需用户确认）
     *
     * @param durationMin 测试时长（分钟），默认 30
     * @param confirmed   是否已获得用户明确同意（高危操作，必须为 true 才真正启动）
     * @param toolContext 工具上下文（后端注入，含 sessionId）
     * @return 确认请求或执行结果
     */
    @Tool(name = "startMemStress", description = "启动内存压力测试（高危操作）。durationMin 为分钟数默认30。此操作会消耗物理内存的70%，可能导致系统卡顿，必须先向用户解释风险并获得明确同意后，将 confirmed 设为 true 再次调用才真正执行。启动后可用 getMemStressStatus 查询内存占用率，用 stopMemStress 停止。")
    public String startMemStress(
            @ToolParam(description = "压测时长，单位分钟，默认30") Integer durationMin,
            @ToolParam(description = "是否已获得用户明确同意（高危操作需为 true，默认 false）") Boolean confirmed,
            ToolContext toolContext) {

        boolean isConfirmed = Boolean.TRUE.equals(confirmed);
        Long sessionId = toolContext != null
                ? (Long) toolContext.getContext().get("sessionId")
                : null;

        // 高危操作：未获用户明确同意前，不启动，返回确认请求
        if (!isConfirmed) {
            log.info("内存压测需用户确认: sessionId={}", sessionId);
            return "【需要用户确认】内存压力测试会消耗电脑物理内存的约70%（" 
                    + "以你电脑32GB内存为例约占用22GB），可能导致系统卡顿、其他程序运行缓慢，"
                    + "但不会删除任何数据。请向用户明确说明这个影响，询问用户是否同意进行内存压测。"
                    + "用户明确同意后，将 confirmed 设为 true 重新调用本工具执行。";
        }

        int minutes = (durationMin == null || durationMin <= 0) ? 30 : durationMin;
        log.info("调用工具: startMemStress，用户已确认，启动内存压测，时长={}分钟", minutes);

        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("durationMin", minutes);
            var task = taskService.startTask("memstress", params, sessionId);
            log.info("内存压测任务已创建: taskId={}", task.getId());
            return "内存压力测试已启动（用户已确认），时长 " + minutes + " 分钟，taskId=" + task.getId()
                    + "。将通过 Testlimit 消耗约70%物理内存。可用 getMemStressStatus 查询实时内存占用率，或用 stopMemStress 提前停止。";
        } catch (Exception e) {
            log.error("启动内存压测失败", e);
            return "内存压测启动失败：" + e.getMessage();
        }
    }

    /**
     * 查询内存压测实时状态（真实内存占用率）
     *
     * @param taskId 压测任务 ID
     * @return 任务状态 + 内存占用率 + 进度
     */
    @Tool(name = "getMemStressStatus", description = "查询内存压测任务的实时状态，返回任务状态、真实内存占用率(%)和进度(%)，用于判断压测是否在进行。")
    public String getMemStressStatus(
            @ToolParam(description = "压测任务 ID（由 startMemStress 返回）") Long taskId) {
        log.info("调用工具: getMemStressStatus，查询内存压测状态，taskId={}", taskId);
        try {
            Map<String, Object> data = taskService.getFurmarkStatus(taskId);
            return "taskId=" + data.get("taskId")
                    + ", status=" + data.get("status")
                    + ", memUsage=" + data.get("memUsage")
                    + "%, progress=" + data.get("progress")
                    + "%";
        } catch (Exception e) {
            log.error("查询内存压测状态失败", e);
            return "查询失败：" + e.getMessage();
        }
    }

    /**
     * 手动停止内存压测
     *
     * @param taskId 压测任务 ID
     * @return 停止结果
     */
    @Tool(name = "stopMemStress", description = "停止内存压测任务，释放内存（必须调用本工具才能真正终止 Testlimit 进程并释放内存）。当用户要求停止压测、关闭压测、结束压测时，必须调用本工具，绝不能只回复文字而不调用。taskId 为 startMemStress 返回的任务 ID，可从对话历史中获得。停止后本工具会验证 Testlimit 进程是否真正清理，若未清理会明确告知。")
    public String stopMemStress(
            @ToolParam(description = "压测任务 ID（由 startMemStress 返回）") Long taskId) {
        log.info("调用工具: stopMemStress，停止内存压测，taskId={}", taskId);
        if (taskId == null) {
            return "请提供要停止的压测任务 ID（由 startMemStress 返回）。";
        }
        try {
            taskService.stopTask(taskId);
            // 停止后验证 Testlimit 进程是否真正清理（等待清理命令执行完成）
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            boolean testlimitLeft = isTestlimitRunning();
            if (testlimitLeft) {
                log.warn("内存压测停止后仍有 Testlimit 进程残留");
                return "内存压测已停止，taskId=" + taskId + "，但仍有 Testlimit 进程未清理干净！"
                        + "请再次调用 stopMemStress 重试，或提醒用户手动结束 Testlimit64 进程以释放内存。";
            }
            log.info("内存压测停止成功，Testlimit 进程已清理，内存已释放，taskId={}", taskId);
            return "内存压测已停止，taskId=" + taskId + "。已验证 Testlimit 进程全部清理，内存已释放。";
        } catch (Exception e) {
            log.error("停止内存压测失败", e);
            return "停止失败：" + e.getMessage();
        }
    }

    /**
     * 检测 Testlimit 进程是否仍存活（用于验证清理是否成功）
     */
    private boolean isTestlimitRunning() {
        try {
            String cmd = "@(Get-Process -Name Testlimit64 -ErrorAction SilentlyContinue).Count";
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
                    return Integer.parseInt(line.trim()) > 0;
                }
            }
            process.waitFor();
        } catch (Exception e) {
            log.warn("检测 Testlimit 进程失败: {}", e.getMessage());
        }
        return false;
    }
}
