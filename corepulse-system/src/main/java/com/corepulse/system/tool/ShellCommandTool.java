package com.corepulse.system.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 终端命令执行工具
 * <p>
 * 让 LLM 能直接执行终端命令（cmd.exe /c），降低对 Hermes 的依赖。
 * <p>
 * 安全策略：
 * - 危险命令（删除/格式化/关机/改注册表等）必须二次确认，把命令原文展示给用户
 * - LLM 必须解释命令执行后会发生什么
 * - 安全命令直接执行，输出截断后返回给 LLM
 */
@Slf4j
@Component
public class ShellCommandTool implements SystemTool {

    /** 输出最大长度（避免撑爆 LLM 上下文） */
    private static final int MAX_OUTPUT_LENGTH = 3000;

    /** 危险命令关键字（涉及删除/破坏/系统级操作） */
    private static final List<String> DANGEROUS_KEYWORDS = List.of(
            "del ", "rm ", "rd ", "rmdir", "format", "shutdown", "reboot",
            "reg delete", "reg add", "sc delete", "net user", "net localgroup",
            "attrib -s", "takeown", "cipher /w", "diskpart", "clean",
            "erase", "move /y", "ren", "xcopy /y /e /c /h /r", "replace"
    );

    /** 完全禁止的命令（即使确认也不执行，太过危险） */
    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "format c:", "format d:", "format e:", "clean all",
            "del /f /s /q c:\\windows", "rd /s /q c:\\windows",
            "rm -rf", "shutdown /r /t 0", "shutdown /s /t 0"
    );

    @Override
    public String toolName() {
        return "run_shell_command";
    }

    @Override
    public String toolDescription() {
        return "执行终端命令（cmd）。可查询系统信息、检测进程、查看磁盘等。危险命令需用户确认。";
    }

    /**
     * 执行终端命令
     * <p>
     * 危险命令返回确认请求（不执行），LLM 向用户解释并展示命令原文；
     * 用户确认后 LLM 再次调用本方法（带 confirm=true）真正执行。
     *
     * @param command  要执行的命令
     * @param confirmed 是否为用户确认后执行（危险命令时需 true）
     * @param toolContext 工具上下文
     * @return 命令输出或确认请求
     */
    @Tool(name = "runShellCommand", description = "执行终端命令。command 为要执行的命令。若命令危险（删除/格式化等），需要先向用户解释并展示命令原文，用户确认后将 confirmed 设为 true 再执行。")
    public String runShellCommand(
            @ToolParam(description = "要执行的终端命令") String command,
            @ToolParam(description = "是否已获得用户确认（危险命令需为 true），默认 false") Boolean confirmed,
            ToolContext toolContext) {

        Long sessionId = toolContext != null
                ? (Long) toolContext.getContext().get("sessionId")
                : null;
        boolean isConfirmed = Boolean.TRUE.equals(confirmed);

        // 1. 检查是否完全禁止的命令
        for (String forbidden : FORBIDDEN_KEYWORDS) {
            if (command.toLowerCase().contains(forbidden)) {
                log.warn("禁止执行的危险命令被请求: command={}, sessionId={}", command, sessionId);
                return "该命令属于高危操作，系统禁止执行：" + command;
            }
        }

        // 2. 检测是否为危险命令
        boolean isDangerous = isDangerous(command);
        if (isDangerous && !isConfirmed) {
            log.info("危险命令需要用户确认: command={}, sessionId={}", command, sessionId);
            // 返回确认请求，LLM 需向用户解释命令作用
            return "【需要用户确认】命令: " + command
                    + "。请向用户解释这条命令执行后会发生什么，并展示命令原文，等待用户确认。"
                    + "用户确认后，将 confirmed 设为 true 重新调用本工具执行。";
        }

        // 3. 执行命令
        log.info("执行终端命令: command={}, confirmed={}, sessionId={}", command, isConfirmed, sessionId);
        String output = executeCommand(command);
        String truncated = truncate(output, MAX_OUTPUT_LENGTH);
        log.info("命令执行完成: command={}, 输出长度={}", command, output.length());
        return truncated;
    }

    /** 判断命令是否为危险命令 */
    private boolean isDangerous(String command) {
        String lower = command.toLowerCase();
        for (String keyword : DANGEROUS_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 执行 cmd 命令 */
    private String executeCommand(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            process.waitFor();
            return output.toString().trim();
        } catch (Exception e) {
            log.error("命令执行失败: {}", command, e);
            return "命令执行失败: " + e.getMessage();
        }
    }

    /** 截断长文本 */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n...(输出过长已截断)";
    }
}
