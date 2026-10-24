package com.corepulse.chat.service;

import com.corepulse.chat.event.ChatEventPublisher;
import com.corepulse.chat.model.dto.ChatSendRequest;
import com.corepulse.chat.model.vo.ChatSendVO;
import com.corepulse.system.tool.ShellCommandExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 命令执行结果播报
 * <p>
 * 职责边界：命令"执行"由 {@code ShellCommandExecutor} 完成（确定性，不依赖模型）；
 * 本类只负责执行完之后"能不能把这件事讲给用户听"（概率性，允许失败）。
 * 两者分开的意义在于：播报失败、模型抽风、超时，都不会改变"命令已经执行完"这个事实，
 * 也不会让用户以为命令没跑。
 * <p>
 * 触发路径：用户在界面点确认 -> 后端执行 -> 走到这里 -> 排一次会话锁 -> 触发一轮 LLM 推理
 * -> 通过 WebSocket 把回复推到界面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandResultNoticeService {

    /** 后台播报最多排队等待会话锁的秒数：超过就不播了，避免无限期占着命令线程 */
    private static final long NOTICE_WAIT_SECONDS = 30;

    /** 播报轮次的来源标记：入库到 extra.source，前端据此不把它渲染成用户气泡 */
    private static final String SOURCE_COMMAND_RESULT = "command-result";

    private final SessionChatDispatcher sessionChatDispatcher;

    private final ChatEventPublisher chatEventPublisher;

    private final ShellCommandExecutor shellCommandExecutor;

    /**
     * 命令执行完后触发一轮推理，把结果说给用户听
     * <p>
     * 失败一律只记日志：这一步是体验增强，不属于业务事实，不能反过来影响命令结果。
     *
     * @param sessionId 会话 ID
     * @param command   命令原文
     * @param result    执行结果
     */
    public void notifyFinished(Long sessionId, String command, ShellCommandExecutor.ExecResult result) {
        if (sessionId == null || result == null) {
            return;
        }
        ChatSendRequest request = new ChatSendRequest(sessionId, buildPrompt(command, result), SOURCE_COMMAND_RESULT);
        ChatSendVO vo = sessionChatDispatcher.trySend(request, NOTICE_WAIT_SECONDS);
        if (vo == null || vo.getReply() == null || vo.getReply().isBlank()) {
            return;
        }
        // 这一步没有 HTTP 响应承接回复，必须走 WebSocket，否则用户看不到模型说了什么
        chatEventPublisher.publishAssistantReply(sessionId, vo.getReply());
        log.info("命令结果播报完成: sessionId={}, replyLen={}", sessionId, vo.getReply().length());
    }

    /**
     * 构造播报提示词
     * <p>
     * 三个约束是有意为之：
     * 1. 显式标注"系统通知，非用户输入"——否则模型会以为用户在催它，语气跑偏；
     * 2. 把退出码和摘要直接给出——虽然上下文里有 tool 记录，但模型不一定去找，
     *    给事实比让它自己检索更稳，也避免它编造成功/失败；
     * 3. 禁止再次调用 runShellCommand——否则模型可能"再执行一次确认一下"，
     *    而工具的确认授权只对同一条 PENDING/CONFIRMED 记录有效，重复调用会生成新的待确认卡片。
     */
    private String buildPrompt(String command, ShellCommandExecutor.ExecResult result) {
        String status = result.success()
                ? "成功（退出码 0）"
                : "失败（退出码 " + result.exitCode() + "）";
        return "【系统通知，不是用户输入】用户刚刚在界面上点击确认，系统已执行完下面这条命令：\n"
                + "命令：" + command + "\n"
                + "执行结果：" + status + "\n"
                + "输出摘要：" + shellCommandExecutor.summarize(result) + "\n"
                + "请用一到两句话告诉用户执行结果，失败时给出可操作的下一步建议。"
                + "不要重复整段输出，不要说命令正在执行，也不要再次调用 runShellCommand 执行同一条命令。";
    }
}
