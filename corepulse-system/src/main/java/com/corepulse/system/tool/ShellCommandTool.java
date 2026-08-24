package com.corepulse.system.tool;

import com.corepulse.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 终端命令执行工具
 * <p>
 * 让 LLM 能直接执行终端命令（cmd.exe /c），实现系统信息查询、进程检测、磁盘清理等操作。
 * <p>
 * 安全策略（三层）：
 * - 只读命令（查询类）直接执行，永不弹确认
 * - 危险命令（删除/格式化/改名/建链接/关机/改注册表等）必须逐条二次确认，把命令原文展示给用户，
 *   会话授权不能豁免
 * - 非危险的写类普通命令同样逐条确认：会话授权仅限只读/查询类命令，
 *   防止用户授权"读取命令不用问"后，rename/mklink 等写操作被静默执行
 * <p>
 * 会话级授权记忆存 Redis（chat:readonly-auth:{sessionId}，TTL 24h）：
 * 用户确认过命令、或明确表达"读取命令直接执行不用问我"时置位。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShellCommandTool implements SystemTool {

    /** 输出最大长度（避免撑爆 LLM 上下文） */
    private static final int MAX_OUTPUT_LENGTH = 3000;

    /** 会话级只读授权有效期 */
    private static final Duration READONLY_AUTH_TTL = Duration.ofHours(24);

    /** 只读命令首词白名单（查询类，直接执行不弹确认） */
    private static final List<String> READONLY_FIRST_WORDS = List.of(
            "powershell", "dir", "wmic", "tasklist", "systeminfo",
            "type", "where", "netstat", "ipconfig"
    );

    /** PowerShell 写操作 cmdlet 特征（出现即不视为只读） */
    private static final List<String> PS_WRITE_MARKERS = List.of(
            "remove-", "set-", "new-", "clear-", "stop-", "start-",
            "add-", "move-", "copy-", "rename-", "delete", "invoke-",
            "export-", "tee-", "-verb runas", "out-file", "set-content", "add-content"
    );

    /**
     * 危险命令关键字（涉及删除/破坏/系统级操作）。
     * 按词边界匹配，避免 Format-Table 命中 format、普通单词命中 ren 等误报。
     */
    private static final List<String> DANGEROUS_KEYWORDS = List.of(
            "del", "rm", "rd", "rmdir", "format", "shutdown", "reboot",
            "reg delete", "reg add", "sc delete", "net user", "net localgroup",
            "attrib -s", "takeown", "cipher /w", "diskpart", "clean",
            "erase", "move /y", "ren", "rename", "mklink",
            "xcopy /y /e /c /h /r", "replace"
    );

    /** 完全禁止的命令（即使确认也不执行，太过危险） */
    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "format c:", "format d:", "format e:", "clean all",
            "del /f /s /q c:\\windows", "rd /s /q c:\\windows",
            "rm -rf", "shutdown /r /t 0", "shutdown /s /t 0",
            // cleanmgr 为 GUI 弹窗程序，无法自动化，会挂死命令
            "cleanmgr"
    );

    private final StringRedisTemplate redisTemplate;

    @Override
    public String toolName() {
        return "run_shell_command";
    }

    @Override
    public String toolDescription() {
        return "执行终端命令（cmd）。只读查询命令直接执行；危险命令需用户逐条确认；用户授权后会话内非危险命令免确认。";
    }

    /**
     * 执行终端命令
     * <p>
     * 危险命令返回确认请求（不执行），LLM 向用户解释并展示命令原文；
     * 用户确认后 LLM 再次调用本方法（带 confirmed=true）真正执行。
     *
     * @param command  要执行的命令
     * @param confirmed 是否为用户确认后执行（危险命令时需 true）
     * @param toolContext 工具上下文
     * @return 命令输出或确认请求
     */
    @Tool(name = "runShellCommand", description = "执行终端命令。command 为要执行的命令。只读查询命令（dir/wmic/tasklist/powershell 查询等）可直接执行；"
            + "写操作/危险命令（rename/mklink/robocopy 覆盖/删除等）必须先向用户解释并展示命令原文，用户确认后将 confirmed 设为 true 再执行，"
            + "会话授权只对只读查询命令生效，写操作仍需逐条确认。")
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
                return "该命令属于高危操作或无法自动化执行，系统禁止执行：" + command;
            }
        }

        // 2. 只读查询命令：直接执行，永不弹确认
        if (isReadOnly(command)) {
            log.info("只读命令直接执行: command={}, sessionId={}", command, sessionId);
            return doExecute(command, sessionId);
        }

        // 3. 检测是否为危险命令（危险命令始终逐条确认，不受会话授权影响）
        if (isDangerous(command) && !isConfirmed) {
            log.info("危险命令需要用户确认: command={}, sessionId={}", command, sessionId);
            return "【需要用户确认】命令: " + command
                    + "。请向用户解释这条命令执行后会发生什么，并展示命令原文，等待用户确认。"
                    + "用户确认后，将 confirmed 设为 true 重新调用本工具执行。";
        }

        // 4. 非危险的写类普通命令：一律逐条确认（会话授权仅限只读命令，不豁免写操作，
        //    避免用户授权"读取命令不用问"后 rename/mklink 等被静默执行）
        if (!isConfirmed) {
            log.info("命令需用户确认（写操作不受会话授权豁免）: command={}, sessionId={}", command, sessionId);
            return "【需要用户确认】命令: " + command
                    + "。请向用户解释这条命令执行后会发生什么，并展示命令原文，等待用户确认。"
                    + "用户确认后，将 confirmed 设为 true 重新调用本工具执行。"
                    + "（若用户表示读取/查询类命令以后不用逐条询问，请调用 authorizeReadonlyCommands 记住授权。）";
        }

        // 用户本次确认即视为授权该会话后续非危险命令免确认
        if (isConfirmed) {
            grantReadonlyAuth(sessionId);
        }
        return doExecute(command, sessionId);
    }

    /**
     * 记住本会话的只读/查询类命令免确认授权
     * <p>
     * 用户明确表达"读取命令直接执行，不用问我"时由 LLM 调用。
     *
     * @param toolContext 工具上下文
     * @return 授权结果描述
     */
    @Tool(name = "authorizeReadonlyCommands", description = "记住用户授权：本次会话内只读/查询类命令不再逐条询问确认。"
            + "当用户明确表达\"读取命令直接执行\"\"不用问我\"\"以后读取类命令不用确认\"等意思时，调用本工具记住授权。")
    public String authorizeReadonlyCommands(ToolContext toolContext) {
        Long sessionId = toolContext != null
                ? (Long) toolContext.getContext().get("sessionId")
                : null;
        if (sessionId == null) {
            return "授权失败：无法获取会话 ID。";
        }
        grantReadonlyAuth(sessionId);
        log.info("会话 {} 已获得只读命令免确认授权", sessionId);
        return "已记住授权：本次会话内只读/查询类命令将直接执行，不再逐条询问。请告知用户授权已生效。";
    }

    /** 实际执行命令并返回截断后的输出 */
    private String doExecute(String command, Long sessionId) {
        log.info("执行终端命令: command={}, sessionId={}", command, sessionId);
        String output = executeCommand(command);
        log.info("命令执行完成: command={}, 输出长度={}", command, output.length());
        return truncate(output, MAX_OUTPUT_LENGTH);
    }

    /**
     * 判断命令是否为纯只读查询命令
     * <p>
     * 条件：首词属于查询类白名单，且不含输出重定向、不含命令拼接、不含危险词；
     * powershell 开头时还需不含写操作 cmdlet。
     */
    private boolean isReadOnly(String command) {
        String lower = command.trim().toLowerCase();
        if (lower.isEmpty()) {
            return false;
        }
        // 输出重定向 / & 命令拼接会引入写操作风险，一律不视为只读
        if (lower.contains(">") || lower.contains("&")) {
            return false;
        }
        String firstWord = lower.split("[\\s]+", 2)[0];
        // 精细识别 sc / reg 的"查询子命令"：sc query、reg query 是只读查询，直接执行；
        // 但 sc delete、reg add 等写操作不视为只读（会被 isDangerous 拦截或走确认流程）
        if ("sc".equals(firstWord) || "reg".equals(firstWord)) {
            String secondWord = lower.split("[\\s]+", 3).length > 1
                    ? lower.split("[\\s]+", 3)[1]
                    : "";
            if (!"query".equals(secondWord)) {
                return false;  // sc/reg 非 query 子命令，不视为只读
            }
        } else if (!READONLY_FIRST_WORDS.contains(firstWord)) {
            return false;
        }
        // 管道：仅 powershell 内查询 cmdlet 间管道放行（各段已由危险词/写 cmdlet 检查覆盖），
        // 其他命令的管道可能接危险程序，不视为只读
        if (lower.contains("|") && !"powershell".equals(firstWord)) {
            return false;
        }
        if (isDangerous(lower)) {
            return false;
        }
        if ("powershell".equals(firstWord)) {
            for (String marker : PS_WRITE_MARKERS) {
                if (lower.contains(marker)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 判断命令是否为危险命令（按词边界匹配，避免子串误报） */
    private boolean isDangerous(String command) {
        String lower = command.toLowerCase();
        for (String keyword : DANGEROUS_KEYWORDS) {
            // \b 词边界：format 不会命中 Format-Table，ren 不会命中普通单词内部
            if (Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b").matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }

    /** 查询会话是否已获得只读命令免确认授权 */
    private boolean isReadonlyAuthorized(Long sessionId) {
        if (sessionId == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.readonlyAuth(sessionId)));
        } catch (Exception e) {
            log.warn("查询只读授权失败，按未授权处理: sessionId={}", sessionId, e);
            return false;
        }
    }

    /** 写入会话级只读命令免确认授权（TTL 24h） */
    private void grantReadonlyAuth(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(RedisKeys.readonlyAuth(sessionId), "1", READONLY_AUTH_TTL);
        } catch (Exception e) {
            log.warn("写入只读授权失败: sessionId={}", sessionId, e);
        }
    }

    /** 执行 cmd 命令：前置 chcp 65001 让支持代码页的命令（dir 等 cmd 内置命令）按 UTF-8 输出；
     *  robocopy 等老式工具无视代码页仍按系统 ANSI 输出，因此先按 UTF-8 解码，
     *  出现替换符（乱码特征）时对同一份字节改用 GBK 解码，命令只执行一次 */
    private String executeCommand(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "chcp 65001 >nul & " + command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            byte[] bytes = process.getInputStream().readAllBytes();
            process.waitFor();
            String output = new String(bytes, StandardCharsets.UTF_8);
            if (output.indexOf('\uFFFD') >= 0) {
                output = new String(bytes, Charset.forName("GBK"));
            }
            return output.trim();
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
