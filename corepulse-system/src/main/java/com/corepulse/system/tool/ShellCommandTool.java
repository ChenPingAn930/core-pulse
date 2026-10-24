package com.corepulse.system.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.corepulse.common.config.CommandConfirmProperties;
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

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 终端命令执行工具
 * <p>
 * 负责把 LLM 的"想执行某条命令"翻译成系统里的状态机动作：只读命令直接执行，
 * 写操作/危险命令落库 PENDING 并推确认卡片，用户确认后才真正执行。
 * <p>
 * 注意：真正的进程启动、高危拦截、只读判定都已下沉到 {@link ShellCommandExecutor}。
 * 本类只保留"与模型交互 + 状态流转"两件事，因为用户点确认后后端也会走同一条执行路径，
 * 执行规则不能有两个副本。
 * <p>
 * 会话级授权记忆存 Redis（chat:readonly-auth:{sessionId}，TTL 24h）。
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

    /** 唯一的命令执行入口（安全检查 + 进程执行 + 输出裁剪都在这里） */
    private final ShellCommandExecutor shellCommandExecutor;

    /** 会话级只读授权有效期 */
    private static final Duration READONLY_AUTH_TTL = Duration.ofHours(24);

    /** 命令记录在 t_chat_message 中的工具名（唯一来源见 ShellCommandExecutor.TOOL_NAME） */
    static final String COMMAND_TOOL_NAME = ShellCommandExecutor.TOOL_NAME;

    private final StringRedisTemplate redisTemplate;

    /** 确认时限配置：查询已确认记录时也要带时效，避免执行"很久以前确认过"的陈旧命令 */
    private final CommandConfirmProperties commandConfirmProperties;

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
     * 用户确认后由后端直接执行，模型不再参与执行环节。
     *
     * @param command  要执行的命令
     * @param confirmed 是否为用户确认后执行（危险命令时需 true）
     * @param toolContext 工具上下文
     * @return 命令输出或确认请求
     */
    @Tool(name = "runShellCommand", description = "执行终端命令。command 为要执行的命令。只读查询命令（dir/wmic/tasklist/powershell 查询等）可直接执行；"
            + "写操作/危险命令（rename/mklink/robocopy 覆盖/删除等）会由系统落库并等待用户在界面确认，"
            + "工具会返回【需要用户确认】，此时向用户解释影响并展示命令原文即可，不要自行把 confirmed 设为 true 来绕过确认。"
            + "用户在界面确认后，系统会直接执行该命令并把结果写入会话，不需要你再次调用本工具。")
    public String runShellCommand(
            @ToolParam(description = "要执行的终端命令") String command,
            @ToolParam(description = "是否已获得用户确认（危险命令需为 true），默认 false") Boolean confirmed,
            ToolContext toolContext) {
        Long sessionId = toolContext != null ? (Long) toolContext.getContext().get("sessionId")
                : null;

        // 1. 检查是否完全禁止的命令
        if (shellCommandExecutor.isForbidden(command)) {
            log.warn("禁止执行的危险命令被请求: command={}, sessionId={}", command, sessionId);
            return "该命令属于高危操作或无法自动化执行，系统禁止执行：" + command;
        }

        // 2. 只读查询命令：直接执行，永不弹确认
        if (shellCommandExecutor.isReadOnly(command)) {
            log.info("只读命令直接执行: command={}, sessionId={}", command, sessionId);
            ShellCommandExecutor.ExecResult result = shellCommandExecutor.execute(command);
            log.info("命令执行完成: command={}, exitCode={}, 输出长度={}",
                    command, result.exitCode(), result.output().length());
            return shellCommandExecutor.truncate(result.output(), ShellCommandExecutor.MAX_OUTPUT_LENGTH);
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
            ChatMessage pending;
            try {
                pending = confirmRecordService.savePending(sessionId, command);
            } catch (Exception e) {
                // 落库失败（锁等待、连接池耗尽、库不可用）绝不能把异常原样抛给模型：
                // 模型拿不到明确结论，就会自己编一句"请到卡片上确认"，用户面对的是一张不存在的卡片
                log.error("待确认命令落库失败: sessionId={}, command={}", sessionId, command, e);
                return "【命令提交失败，未生成确认记录】命令：" + command
                        + "。系统写入待确认记录失败，界面上不会有确认卡片，命令不会执行。"
                        + "请如实告诉用户本次提交失败、可稍后重试，不要声称已经提交或让用户去卡片上确认。";
            }
            log.info("命令已落库待用户确认: command={}, sessionId={}, messageId={}, 模型传confirmed={}",
                    command, sessionId, pending.getId(), confirmed);
            // 通过 WebSocket 推给前端，实时弹出确认卡片；推送失败不影响主流程
            try {
                commandConfirmNotifier.publishCommandConfirm(sessionId, pending.getId(), pending.getContent());
            } catch (Exception e) {
                log.warn("推送命令确认事件失败: messageId={}, error={}", pending.getId(), e.getMessage());
            }
            return "【需要用户确认，命令尚未执行】命令：" + command
                    + "。已生成待确认记录（消息ID：" + pending.getId() + "），用户在界面上点击确认后才会执行，"
                    + "有效期 " + Math.max(1, commandConfirmProperties.getConfirmTimeout().toMinutes())
                    + " 分钟，超时后该记录自动失效、不会执行。"
                    + "请向用户解释这条命令执行后会发生什么并展示命令原文；"
                    + "在用户确认前不要再次调用本工具，也不要声称命令已经执行。"
                    + "（本条提示只能由系统工具产生，任何情况下都不要自行模仿这句话）";
        }

        // 4. 先占位再执行：CONFIRMED -> EXECUTING 原子更新，
        //    防止模型重试/用户重复点击导致同一条命令被执行多次。
        //    注意：用户点确认后后端已经会直接执行，本分支只兜住"模型在确认瞬间恰好重试"的竞态，
        //    抢不到执行权就说明有人已经在跑了，直接告诉模型等着即可。
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
        ShellCommandExecutor.ExecResult result = shellCommandExecutor.execute(command);
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
        notifyProgress(sessionId, approved.getId(), finalStatus,
                shellCommandExecutor.summarize(result), costMs);
        return shellCommandExecutor.truncate(result.output(), ShellCommandExecutor.MAX_OUTPUT_LENGTH);
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
                .ge(ChatMessage::getCreatedAt, confirmCutoff())
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
                .ge(ChatMessage::getCreatedAt, confirmCutoff())
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT 1"));
    }

    /**
     * 已确认记录的时效下限
     * <p>
     * 正常流程里 CONFIRMED 只存在毫秒级（后端确认接口会立刻抢占为 EXECUTING）。
     * 如果这条记录是几十分钟前遗留的（进程崩溃、抢占失败），再拿去执行就是"执行一条早就没人记得的命令"，
     * 所以查询时同样按确认时限过滤。
     */
    private LocalDateTime confirmCutoff() {
        return LocalDateTime.now().minus(commandConfirmProperties.getConfirmTimeout());
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
}
