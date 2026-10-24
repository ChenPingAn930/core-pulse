package com.corepulse.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.corepulse.chat.model.vo.CommandStatusVO;
import com.corepulse.chat.service.CommandPendingService;
import com.corepulse.chat.service.CommandResultNoticeService;
import com.corepulse.chat.service.Command_ConfirmedService;
import com.corepulse.common.config.CommandConfirmProperties;
import com.corepulse.common.event.CommandConfirmNotifier;
import com.corepulse.domain.entity.ChatMessage;
import com.corepulse.domain.enums.CommandStatus;
import com.corepulse.domain.enums.MessageRole;
import com.corepulse.domain.mapper.ChatMessageMapper;
import com.corepulse.system.tool.ShellCommandExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;

/**
 * 危险命令确认
 * <p>
 * 状态流转：PENDING ->（用户点确认）CONFIRMED -> EXECUTING -> EXECUTED / FAILED；
 * 另一条出口是 PENDING ->（超过确认时限）EXPIRED，从未执行、不可再执行。
 * <p>
 * 为什么确认后由后端直接执行，而不是"代发一条 prompt 让模型再调一次工具"：
 * 用户点了确认，代表一次已经确定要执行的动作，不应该把是否执行再交给模型决定。
 * 旧实现需要额外一轮 LLM（十几秒）、同步阻塞 HTTP 请求，并且还要靠"回查状态"猜测
 * 模型到底调没调工具，失败后只能笼统提示"模型未执行该命令"。
 * 现在执行权在后端：命令执行与 LLM 完全解耦，模型只负责事前解释影响、事后读结果做分析。
 * <p>
 * 为什么确认不强加事务：确认动作必须先独立提交（用户点了就是点了），
 * 而执行是几十秒的外呼操作，包在事务里会长时间占用数据库连接，
 * 且执行失败回滚会把"用户已确认"这个事实一起抹掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Command_ConfirmedServiceImpl implements Command_ConfirmedService {

    private final ChatMessageMapper chatMessageMapper;

    /** 唯一的命令执行入口（高危拦截、只读判定、进程执行都在里面） */
    private final ShellCommandExecutor shellCommandExecutor;

    /** 状态收敛事件推送（common 域接口，实现由 web 模块提供） */
    private final CommandConfirmNotifier commandConfirmNotifier;

    /** 执行完的结果播报（触发一轮 LLM，把结果讲给用户听） */
    private final CommandResultNoticeService commandResultNoticeService;

    /** 待确认命令门面：负责超时判定，保证"挂了很久的命令"不会突然被执行 */
    private final CommandPendingService commandPendingService;

    /** 确认时限配置：超时提示文案要和服务端判定用同一个值，避免提示 5 分钟、实际 10 分钟 */
    private final CommandConfirmProperties commandConfirmProperties;

    /** 命令异步执行线程池：确认接口只负责抢执行权，不在请求线程里等命令跑完 */
    @Qualifier("commandExecPool")
    private final ExecutorService commandExecPool;

    /**
     * 用户确认命令 -> 抢占执行权 -> 异步执行 -> 回写终态并推送
     * <p>
     * 接口本身只做三件事：校验、状态机迁移、把任务丢进线程池，因此耗时是毫秒级的。
     */
    @Override
    public String Command_Confirmed(String messageid) {
        Long id = parseId(messageid);
        if (id == null) {
            return "确认失败：消息 ID 非法";
        }

        ChatMessage message = chatMessageMapper.selectById(id);
        if (message == null) {
            return "确认失败：命令记录不存在";
        }
        // 已经是终态/执行中：直接告诉用户当前进展，而不是报错 —— 双击确认、页面重连都会走到这里
        String status = message.getStatus();
        if (CommandStatus.EXECUTING.getValue().equals(status)) {
            return "该命令正在执行中，结果会自动推送到界面";
        }
        if (CommandStatus.EXECUTED.getValue().equals(status)) {
            return "该命令已执行完成，无需重复确认";
        }
        if (CommandStatus.FAILED.getValue().equals(status)) {
            return "该命令已执行过且失败，如需重试请重新发送指令";
        }
        if (CommandStatus.EXPIRED.getValue().equals(status)) {
            return expiredMessage();
        }
        if (!CommandStatus.PENDING.getValue().equals(status)) {
            return "确认失败：该命令已处理过，请勿重复确认";
        }

        // 步骤 0：先判是否已超时。用户可能隔了很久才回来点这张卡片，
        // 此时命令已经不该被执行了 —— 直接拒绝并收敛成 EXPIRED，而不是"点一下就突然跑起来"
        if (commandPendingService.expireIfTimeout(id)) {
            log.info("确认请求命中已超时命令: messageId={}", id);
            publishStatus(message.getSessionId(), id, CommandStatus.EXPIRED,
                    "命令已超时未执行", null);
            return expiredMessage();
        }

        // 步骤 1：记录"用户已确认"这一事实。条件更新保证双击时只有一次生效
        int confirmedRows = chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .set(ChatMessage::getConfirmed, 1)
                .set(ChatMessage::getStatus, CommandStatus.CONFIRMED.getValue())
                .eq(ChatMessage::getId, id)
                .eq(ChatMessage::getConfirmed, 0)
                .eq(ChatMessage::getStatus, CommandStatus.PENDING.getValue()));
        if (confirmedRows == 0) {
            // 并发场景：刚被判超时的命令这次更新会失败，回查一次给准确提示，
            // 否则用户看到"已被处理"会以为命令执行过了
            ChatMessage latest = chatMessageMapper.selectById(id);
            if (latest != null && CommandStatus.EXPIRED.getValue().equals(latest.getStatus())) {
                return expiredMessage();
            }
            return "确认失败：该命令已被处理";
        }
        log.info("命令确认成功: messageId={}, command={}", id, message.getContent());

        // 步骤 2：立刻抢占执行权。CONFIRMED 只是"确认了"，EXECUTING 才是"有人正在执行"，
        // 两步分开是为了让模型侧（工具调用）和后端侧共用同一把锁，谁抢到谁执行，不会重复跑
        int claimedRows = chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .set(ChatMessage::getStatus, CommandStatus.EXECUTING.getValue())
                .eq(ChatMessage::getId, id)
                .eq(ChatMessage::getStatus, CommandStatus.CONFIRMED.getValue()));
        if (claimedRows == 0) {
            return "该命令正在处理中，请勿重复确认";
        }

        // 状态已落库再推事件：前端收到事件后立刻回查，读到的状态和事件一致
        publishStatus(message.getSessionId(), id, CommandStatus.EXECUTING, "命令已开始执行", null);

        try {
            commandExecPool.execute(() -> executeAndRecord(message));
        } catch (Exception e) {
            // 线程池拒绝（队列满/已关闭）很少见，但状态已经进了 EXECUTING，
            // 必须收敛成终态，否则卡片会一直转圈
            log.error("命令任务提交失败: messageId={}", id, e);
            ShellCommandExecutor.ExecResult rejected =
                    new ShellCommandExecutor.ExecResult(false, -1, "执行队列繁忙，命令未执行");
            if (finish(id, message.getSessionId(), rejected)) {
                publishStatus(message.getSessionId(), id, CommandStatus.FAILED,
                        shellCommandExecutor.summarize(rejected), 0L);
            }
            return "确认失败：执行队列繁忙，命令未执行";
        }
        return "命令已开始执行，结果会自动推送到界面";
    }

    /**
     * 线程池里真正执行命令：抢到执行权的记录才会走到这里
     * <p>
     * 执行结果写两处，缺一不可：
     * 1. 命令记录本身置为终态 —— 前端卡片靠它决定是否停止计时；
     * 2. 新增一条 role=tool 的输出记录 —— 模型下一轮读历史时，
     *    命令记录只会被描述成"[已执行命令]（实际输出见后续工具执行记录）"，
     *    真正的事实必须是这条独立记录，否则模型会自己编一个"执行成功"。
     */
    private void executeAndRecord(ChatMessage message) {
        Long id = message.getId();
        Long sessionId = message.getSessionId();
        long startAt = System.currentTimeMillis();

        ShellCommandExecutor.ExecResult result;
        try {
            result = shellCommandExecutor.execute(message.getContent());
        } catch (Exception e) {
            // 执行器内部已兜底异常，这里再兜一层：线程里抛出的异常没人接，会静默丢失
            log.error("命令执行异常: messageId={}", id, e);
            result = new ShellCommandExecutor.ExecResult(false, -1, "命令执行异常: " + e.getMessage());
        }
        long costMs = System.currentTimeMillis() - startAt;

        CommandStatus finalStatus = result.success() ? CommandStatus.EXECUTED : CommandStatus.FAILED;
        if (!finish(id, sessionId, result)) {
            return;
        }
        log.info("命令执行结束: messageId={}, status={}, exitCode={}, costMs={}",
                id, finalStatus, result.exitCode(), costMs);
        // 先推卡片状态再播报：播报要等会话锁 + 一整轮 LLM，不能让卡片跟着一起转圈
        publishStatus(sessionId, id, finalStatus, shellCommandExecutor.summarize(result), costMs);
        commandResultNoticeService.notifyFinished(sessionId, message.getContent(), result);
    }

    /**
     * 把执行结果落库：EXECUTING -> 终态 + 追加一条工具输出记录
     * <p>
     * 条件更新只允许从 EXECUTING 迁移：万一状态被别的路径改过（重复确认、人工修数据），
     * 这里直接放弃写入，避免覆盖别人已经写好的结果。
     *
     * @return true 表示本次成功写入终态，否则说明状态已被他人改写
     */
    private boolean finish(Long id, Long sessionId, ShellCommandExecutor.ExecResult result) {
        CommandStatus finalStatus = result.success() ? CommandStatus.EXECUTED : CommandStatus.FAILED;
        int updated;
        try {
            updated = chatMessageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                    .set(ChatMessage::getStatus, finalStatus.getValue())
                    .eq(ChatMessage::getId, id)
                    .eq(ChatMessage::getStatus, CommandStatus.EXECUTING.getValue()));
        } catch (Exception e) {
            log.error("命令终态回写失败: messageId={}", id, e);
            return false;
        }
        if (updated == 0) {
            log.warn("命令状态已被其他流程改写，跳过结果落库: messageId={}", id);
            return false;
        }
        saveOutputRecord(sessionId, result);
        return true;
    }

    /**
     * 追加一条 role=tool 的输出记录
     * <p>
     * status 保持 null：ChatServiceImpl 组装上下文时，只有带 status 的记录才会被当成
     * "命令确认记录"，status 为 null 的 role=tool 记录才会被还原成"[工具执行记录] 执行结果"。
     * 这里如果误填了 EXECUTED，模型就永远看不到真正的输出内容。
     */
    private void saveOutputRecord(Long sessionId, ShellCommandExecutor.ExecResult result) {
        try {
            ChatMessage output = new ChatMessage();
            output.setSessionId(sessionId);
            output.setRole(MessageRole.TOOL.getValue());
            output.setToolName(ShellCommandExecutor.TOOL_NAME);
            output.setContent(shellCommandExecutor.describeForModel(result));
            chatMessageMapper.insert(output);
        } catch (Exception e) {
            log.error("命令执行结果记录写入失败: sessionId={}", sessionId, e);
        }
    }

    /** 推送状态事件：事件通道只是展示优化，推不出去不能影响主流程（前端还有轮询兜底） */
    private void publishStatus(Long sessionId, Long messageId, CommandStatus status,
                               String summary, Long costMs) {
        try {
            commandConfirmNotifier.publishCommandStatus(
                    sessionId, messageId, status.getValue(), summary, costMs);
        } catch (Exception e) {
            log.warn("命令状态事件推送失败: messageId={}, status={}", messageId, status, e);
        }
    }

    /**
     * 查询命令当前状态（前端兜底通道）
     * <p>
     * 只按主键查一条，O(1)；不做权限校验之外的加工，避免轮询时产生额外开销。
     * 记录不存在返回 null，由控制层决定给前端什么提示。
     */
    @Override
    public CommandStatusVO queryStatus(String messageid) {
        Long id = parseId(messageid);
        if (id == null) {
            return null;
        }
        ChatMessage message = chatMessageMapper.selectById(id);
        if (message == null) {
            return null;
        }
        // 轮询是最容易被触发的兜底通道：这里顺手把超时记录收敛掉，
        // 前端就能直接从 PENDING 跳到 EXPIRED，不用等下一轮定时扫描
        if (CommandStatus.PENDING.getValue().equals(message.getStatus())
                && commandPendingService.expireIfTimeout(id)) {
            message.setStatus(CommandStatus.EXPIRED.getValue());
        }
        return CommandStatusVO.builder()
                .status(message.getStatus())
                .command(message.getContent())
                .build();
    }

    /** 超时提示文案：把配置的时限直接写进提示，避免用户以为是自己点晚了 1 秒 */
    private String expiredMessage() {
        long minutes = Math.max(1, commandConfirmProperties.getConfirmTimeout().toMinutes());
        return "该命令已超时失效（超过 " + minutes + " 分钟未确认），不会被执行，如需执行请重新发送指令";
    }

    /** 消息 ID 是自增 Long，前端传字符串，先转换避免非法 ID 直接打到 SQL */
    private Long parseId(String messageid) {
        if (messageid == null || messageid.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(messageid.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
