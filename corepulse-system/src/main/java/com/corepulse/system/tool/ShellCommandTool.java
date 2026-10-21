package com.corepulse.system.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.corepulse.common.constant.RedisKeys;
import com.corepulse.common.event.CommandConfirmNotifier;
import com.corepulse.domain.entity.ChatMessage;
import com.corepulse.domain.enums.CommandStatus;
import com.corepulse.domain.mapper.ChatMessageMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private final ChatMessageMapper chatMessageMapper;

    /** 待确认命令落库（独立事务，保证 WS 推送后记录已可见） */
    private final CommandConfirmRecordService confirmRecordService;

    /** 命令确认事件推送（common 域接口，实现由 web 模块提供） */
    private final CommandConfirmNotifier commandConfirmNotifier;

    /** 输出最大长度（避免撑爆 LLM 上下文） */
    private static final int MAX_OUTPUT_LENGTH = 3000;

    /** 状态事件里的摘要长度上限（WebSocket 帧不宜过大，卡片也只展示一行） */
    private static final int MAX_STATUS_SUMMARY_LENGTH = 200;

    /** 会话级只读授权有效期 */
    private static final Duration READONLY_AUTH_TTL = Duration.ofHours(24);

    /** 命令记录在 t_chat_message 中的工具名（与 @Tool 的 name 一致，CommandConfirmRecordService 共用） */
    static final String COMMAND_TOOL_NAME = "runShellCommand";

    /**
     * 只读命令首词白名单（查询类，直接执行不弹确认）。
     * <p>
     * 含 findstr / find / more / sort：它们只从 stdin 读、往 stdout 写，是管道的"过滤段"，
     * 只有把它们也认成只读，"tasklist | findstr xxx" 这类常见查询才能整条放行。
     */
    private static final List<String> READONLY_FIRST_WORDS = List.of(
            "powershell", "dir", "wmic", "tasklist", "systeminfo",
            "type", "where", "netstat", "ipconfig",
            "findstr", "find", "more", "sort"
    );

    /**
     * 子命令白名单：首词相同、读写取决于第二个词的命令。
     * <p>
     * 例如 reg query 只读、reg add 写入；sc query 只读、sc delete 删除；
     * schtasks /query 只读、schtasks /delete 删除。表里没登记的第二个词一律按非只读处理。
     */
    private static final Map<String, Set<String>> READONLY_SUBCOMMANDS = Map.of(
            "reg", Set.of("query"),
            "sc", Set.of("query"),
            "schtasks", Set.of("query", "/query")
    );

    /**
     * 无害重定向：2&gt;nul、&gt;nul、&gt;&gt;nul、2&gt;&amp;1、1&gt;&amp;2。
     * <p>
     * 这类写法只是丢弃或合并输出流，不写文件系统。若不做剥离，
     * "reg query ... 2&gt;nul" 这种最常见的静默查询会被整条误判为写操作。
     * 注意只剥离目标是 nul / &amp;1 / &amp;2 的，重定向到真实文件仍按写操作处理。
     */
    private static final Pattern HARMLESS_REDIRECT =
            // nul 后必须是空白或结尾：">nul.txt" 是写文件，不能被当成无害重定向放过
            Pattern.compile("\\d?>>?\\s*(nul(?=\\s|$)|&[12])", Pattern.CASE_INSENSITIVE);

    /**
     * wmic 写操作特征：call 会真正启动进程，create / delete / terminate / set 会改系统状态。
     * <p>
     * 用词边界匹配而非"前后带空格的包含"：后者识别不到 "wmic process where name='x' delete"
     * 这种写操作动词位于命令末尾的写法。
     */
    private static final Pattern WMIC_WRITE_PATTERN =
            Pattern.compile("\\b(call|create|delete|terminate|set)\\b");

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
            + "写操作/危险命令（rename/mklink/robocopy 覆盖/删除等）会由系统落库并等待用户在界面确认，"
            + "工具会返回【需要用户确认】，此时向用户解释影响并展示命令原文即可，不要自行把 confirmed 设为 true 来绕过确认。"
            + "用户确认后系统会自动发起新一轮调用，照常传入相同 command 与 confirmed=true 即可执行。")
    public String runShellCommand(
            @ToolParam(description = "要执行的终端命令") String command,
            @ToolParam(description = "是否已获得用户确认（危险命令需为 true），默认 false") Boolean confirmed,
            ToolContext toolContext) {
        Long sessionId = toolContext != null ? (Long) toolContext.getContext().get("sessionId")
                : null;

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

        // 3. 写操作/危险命令：执行授权只能来自数据库，模型传的 confirmed 仅代表"申请执行"的意图，
        //    不能作为授权依据 —— 否则模型自己填 true 就能绕过用户确认
        if (sessionId == null) {
            log.warn("无法获取会话 ID，写操作已拒绝: command={}", command);
            return "无法获取会话 ID，写操作/危险命令已拒绝执行：" + command;
        }

        ChatMessage approved = findConfirmedCommand(sessionId, command);
        if (approved == null) {
            // 已有一条"用户确认过但命令原文被改写"的记录：拒绝执行，避免确认 A 却执行 B
            ChatMessage confirmedOther = findLatestConfirmedCommand(sessionId);
            if (confirmedOther != null) {
                log.warn("命令与用户确认的内容不一致，已拒绝: 期望={}, 实际={}", confirmedOther.getContent(), command);
                return "【已拒绝执行】本次传入的命令与用户确认的命令不一致。用户确认的是："
                        + confirmedOther.getContent() + "。请原样传入该命令，不要修改命令内容。";
            }
            // 用户还没确认过这条命令：独立事务落库（提交后再推送，前端点确认时一定查得到）
            ChatMessage pending = confirmRecordService.savePending(sessionId, command);
            log.info("命令已落库待用户确认: command={}, sessionId={}, messageId={}, 模型传confirmed={}",
                    command, sessionId, pending.getId(), confirmed);
            // 通过 WebSocket 推给前端，实时弹出确认卡片；推送失败不影响主流程
            try {
                commandConfirmNotifier.publishCommandConfirm(sessionId, pending.getId(), pending.getContent());
            } catch (Exception e) {
                log.warn("推送命令确认事件失败: messageId={}, error={}", pending.getId(), e.getMessage());
            }
            return "【需要用户确认，命令尚未执行】命令：" + command
                    + "。已生成待确认记录（消息ID：" + pending.getId() + "），用户在界面上点击确认后才会执行。"
                    + "请向用户解释这条命令执行后会发生什么并展示命令原文；"
                    + "在用户确认前不要再次调用本工具，也不要声称命令已经执行。";
        }

        // 4. 先占位再执行：CONFIRMED -> EXECUTING 原子更新，
        //    防止模型重试/用户重复点击导致同一条命令被执行多次。
        //    这里不能直接置 EXECUTED：执行要几十秒，置了终态前端就没法区分"正在跑"和"跑完了"
        int claimed = chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .set(ChatMessage::getStatus, CommandStatus.EXECUTING.getValue())
                .eq(ChatMessage::getId, approved.getId())
                .eq(ChatMessage::getStatus, CommandStatus.CONFIRMED.getValue()));
        if (claimed == 0) {
            log.warn("命令已被执行或状态已变更，拒绝重复执行: command={}, messageId={}",
                    command, approved.getId());
            return "该命令已被执行或状态已变更，请勿重复执行：" + command;
        }

        // 用户本次确认即视为授权该会话后续只读/查询命令免确认
        grantReadonlyAuth(sessionId);
        log.info("命令已获用户确认，开始执行: command={}, messageId={}", command, approved.getId());

        // 状态已提交后再推事件：前端收到事件立刻回查也能读到一致状态
        notifyProgress(sessionId, approved.getId(), CommandStatus.EXECUTING, "命令已开始执行", null);

        long startAt = System.currentTimeMillis();
        ExecResult result = executeCommand(command);
        long costMs = System.currentTimeMillis() - startAt;
        CommandStatus finalStatus = result.success() ? CommandStatus.EXECUTED : CommandStatus.FAILED;

        // 终态回写同样带条件：只允许从 EXECUTING 迁移，避免并发下互相覆盖
        chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .set(ChatMessage::getStatus, finalStatus.getValue())
                .eq(ChatMessage::getId, approved.getId())
                .eq(ChatMessage::getStatus, CommandStatus.EXECUTING.getValue()));

        log.info("命令执行结束: messageId={}, status={}, exitCode={}, costMs={}",
                approved.getId(), finalStatus, result.exitCode(), costMs);
        // 摘要截断后再推：完整输出可能很长，也可能含用户路径等个人信息
        notifyProgress(sessionId, approved.getId(), finalStatus, summarize(result), costMs);
        return truncate(result.output(), MAX_OUTPUT_LENGTH);
    }

    /**
     * 查询用户已确认、尚未执行、且命令原文完全一致的记录
     * <p>
     * 必须比对命令原文：否则用户确认了 A 命令，模型把参数改成 B 命令再带 confirmed=true，
     * 就会执行一条从未被确认过的命令。
     */
    private ChatMessage findConfirmedCommand(Long sessionId, String command) {
        return chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getToolName, COMMAND_TOOL_NAME)
                .eq(ChatMessage::getContent, command)
                .eq(ChatMessage::getConfirmed, 1)
                .eq(ChatMessage::getStatus, CommandStatus.CONFIRMED.getValue())
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT 1"));
    }

    /** 查询该会话最新一条"用户已确认、尚未执行"的命令记录（用于识别命令被改写的情况） */
    private ChatMessage findLatestConfirmedCommand(Long sessionId) {
        return chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .eq(ChatMessage::getToolName, COMMAND_TOOL_NAME)
                .eq(ChatMessage::getConfirmed, 1)
                .eq(ChatMessage::getStatus, CommandStatus.CONFIRMED.getValue())
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT 1"));
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

    /** 实际执行命令并返回截断后的输出（只读分支直接调用，不需要状态推送） */
    private String doExecute(String command, Long sessionId) {
        log.info("执行终端命令: command={}, sessionId={}", command, sessionId);
        ExecResult result = executeCommand(command);
        log.info("命令执行完成: command={}, exitCode={}, 输出长度={}",
                command, result.exitCode(), result.output().length());
        return truncate(result.output(), MAX_OUTPUT_LENGTH);
    }

    /**
     * 推送命令状态（执行中 / 成功 / 失败）
     * <p>
     * 推送失败只记 warn：前端还有轮询兜底，不能因为推不出去就影响命令本身的执行与状态落库。
     */
    private void notifyProgress(Long sessionId, Long messageId, CommandStatus status,
                                String summary, Long costMs) {
        if (sessionId == null || messageId == null) {
            return;
        }
        try {
            commandConfirmNotifier.publishCommandStatus(
                    sessionId, messageId, status.getValue(), summary, costMs);
        } catch (Exception e) {
            log.warn("推送命令状态失败: messageId={}, status={}, error={}",
                    messageId, status, e.getMessage());
        }
    }

    /** 生成给前端展示的一句摘要：成功/失败 + 退出码 + 输出前若干字符 */
    private String summarize(ExecResult result) {
        String prefix = result.success()
                ? "执行成功（退出码 0）"
                : "执行失败（退出码 " + result.exitCode() + "）";
        String body = result.output() == null ? "" : result.output().trim();
        if (body.isEmpty()) {
            return prefix;
        }
        // 压成单行：卡片只有一行摘要位置，多行原文已经在命令区展示过
        String oneLine = truncate(body, MAX_STATUS_SUMMARY_LENGTH)
                .replace("\r", " ")
                .replace("\n", " ")
                .trim();
        return prefix + "：" + oneLine;
    }

    /** 命令执行结果：success 以退出码为准，启动异常按失败处理并给出 -1 */
    record ExecResult(boolean success, int exitCode, String output) {
    }

    /**
     * 判断命令是否为纯只读查询命令
     * <p>
     * 从"只要含拼接/管道就一票否决"改为"分段逐段校验"：cmd 的 &amp; / &amp;&amp; / || / | 只是把多条命令串起来，
     * 整条是否只读取决于每一段是否只读。常见写法 "reg query ... 2&gt;nul &amp; reg query ..." 和
     * "tasklist | findstr xxx" 因此能正常放行，而 "dir &amp; del x" 仍会因 del 段不合法被拦住。
     * <p>
     * 判定顺序（任一不满足即非只读）：
     * 1. 剔除无害重定向（2&gt;nul / &gt;nul / 2&gt;&amp;1）后仍残留 &gt; 或 &lt; —— 说明在写真实文件，拒绝
     * 2. 出现 ^ 转义符 —— 拆分结果可能与实际执行不一致，拒绝
     * 3. 按 &amp; &amp;&amp; || | 拆段，每段都必须通过 isReadOnlySegment
     */
    private boolean isReadOnly(String command) {
        if (command == null) {
            return false;
        }
        String lower = HARMLESS_REDIRECT.matcher(command.trim()).replaceAll(" ")
                .toLowerCase(Locale.ROOT);
        if (lower.isBlank()) {
            return false;
        }
        // ^ 是 cmd 的转义符（如 ^& 表示字面 &），出现即放弃解析：宁可多问一句，也不猜
        if (lower.contains("^")) {
            return false;
        }
        // 引号必须成对，否则无法确定 cmd 把哪段当字符串
        if (lower.chars().filter(c -> c == '"').count() % 2 != 0) {
            return false;
        }
        for (String segment : splitCommand(lower)) {
            if (!isReadOnlySegment(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按 cmd 的命令分隔符拆段：&amp;（顺序执行）、&amp;&amp;（前一条成功才执行）、||（前一条失败才执行）、|（管道）。
     * 连续分隔符按一个处理，空段会在 isReadOnlySegment 里被判为非只读。
     * <p>
     * 双引号内的分隔符不拆：cmd 不解析引号内的 &amp; 和 |，它们会原样传给子进程
     * （如 powershell -Command "a | b" 里的 | 是 PowerShell 管道，不是 cmd 管道）。
     * 只认双引号——cmd 里单引号不是引号字符，'a &amp; del x' 仍会被 cmd 拆成两条命令，
     * 因此单引号必须照常参与拆分，否则会留下一条可绕过校验的命令。
     */
    private List<String> splitCommand(String lower) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                current.append(c);
                continue;
            }
            if (!inQuote && (c == '&' || c == '|')) {
                segments.add(current.toString().trim());
                current.setLength(0);
                while (i + 1 < lower.length()
                        && (lower.charAt(i + 1) == '&' || lower.charAt(i + 1) == '|')) {
                    i++;
                }
            } else {
                current.append(c);
            }
        }
        segments.add(current.toString().trim());
        return segments;
    }

    /** 校验单段命令是否只读：无残留重定向 + 首词（或子命令）在白名单 + 不含危险词/写操作特征 */
    private boolean isReadOnlySegment(String segment) {
        if (segment.isEmpty()) {
            return false;
        }
        // 无害重定向已在入口剥离，这里还出现 > < 说明在读写真实文件
        if (segment.indexOf('>') >= 0 || segment.indexOf('<') >= 0) {
            return false;
        }
        String[] tokens = segment.split("\\s+");
        // 首词归一化：Windows 上同一命令常带扩展名（where.exe / tasklist.exe / reg.exe），
        // 白名单按裸命令名登记，不剥离扩展名就永远匹配不上，只会被误判成写操作
        String firstWord = stripExecutableSuffix(tokens[0]);
        Set<String> allowedSubCommands = READONLY_SUBCOMMANDS.get(firstWord);
        if (allowedSubCommands != null) {
            // 读写同名的命令只认登记过的子命令：reg query 放行，reg add / sc delete 拒绝
            if (tokens.length < 2 || !allowedSubCommands.contains(tokens[1])) {
                return false;
            }
        } else if (!READONLY_FIRST_WORDS.contains(firstWord)) {
            return false;
        }
        if (isDangerous(segment)) {
            return false;
        }
        if ("powershell".equals(firstWord) && containsAny(segment, PS_WRITE_MARKERS)) {
            return false;
        }
        if ("wmic".equals(firstWord) && WMIC_WRITE_PATTERN.matcher(segment).find()) {
            return false;
        }
        return true;
    }

    /**
     * 去掉命令名末尾的 .exe / .com / .bat / .cmd 后缀，让 where.exe 与 where 等价
     * <p>
     * 只处理首词，且只认这四种可执行后缀：白名单之外的名字（如 cmd.exe）剥离后仍然不在名单内，
     * 危险词检查也依旧针对命令原文，不会因此放宽。
     */
    private String stripExecutableSuffix(String token) {
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return token;
        }
        String ext = token.substring(dot + 1);
        boolean executable = "exe".equals(ext) || "com".equals(ext)
                || "bat".equals(ext) || "cmd".equals(ext);
        return executable ? token.substring(0, dot) : token;
    }

    /** 是否命中任一特征词（特征词自带分隔符或边界语义，直接用包含判断） */
    private boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 判断命令是否为危险命令（按词边界匹配，避免子串误报） */
    private boolean isDangerous(String command) {
        String lower = command.toLowerCase();
        for (String keyword : DANGEROUS_KEYWORDS) {
            // \b 词边界：ren 不会命中普通单词内部；
            // (?!-) 排除 PowerShell 的 Verb-Noun 形式 cmdlet：Format-List 是只读的格式化命令，
            // 不能被危险词 format（格式化磁盘）误伤
            if (Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b(?!-)").matcher(lower).find()) {
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
     *  出现替换符（乱码特征）时对同一份字节改用 GBK 解码，命令只执行一次；
     *  返回退出码，供上层判定 EXECUTED / FAILED */
    private ExecResult executeCommand(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "chcp 65001 >nul & " + command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            byte[] bytes = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            String output = new String(bytes, StandardCharsets.UTF_8);
            if (output.indexOf('\uFFFD') >= 0) {
                output = new String(bytes, Charset.forName("GBK"));
            }
            return new ExecResult(exitCode == 0, exitCode, output.trim());
        } catch (Exception e) {
            log.error("命令执行失败: {}", command, e);
            return new ExecResult(false, -1, "命令执行失败: " + e.getMessage());
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
