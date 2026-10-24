package com.corepulse.chat.controller;

import com.corepulse.chat.model.vo.CommandStatusVO;
import com.corepulse.chat.model.vo.PendingCommandVO;
import com.corepulse.chat.service.CommandPendingService;
import com.corepulse.chat.service.Command_ConfirmedService;
import com.corepulse.common.result.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/Confirmed")
public class ConfirmController {

    @Autowired
    Command_ConfirmedService command_ConfirmedService;

    @Autowired
    CommandPendingService commandPendingService;

    /**
     * 查询会话内仍然有效的待确认命令（卡片兜底通道）
     * <p>
     * 为什么需要它：确认卡片现在只靠 WebSocket 事件渲染，页面刷新、断线重连、
     * 推送失败都会让卡片永久消失，而对话正文里还留着"请在卡片上确认"的文字。
     * 前端进入会话时拉一次这个接口，就能把漏掉的卡片补齐。
     * <p>
     * 已过期的记录不会被返回（服务端会顺手把它们收敛成 EXPIRED）。
     *
     * @param sessionId 会话 ID
     * @return 待确认命令列表，可能为空
     */
    @GetMapping("/pending")
    public ApiResponse<List<PendingCommandVO>> listPending(@RequestParam Long sessionId) {
        // 控制器没有事务，这里的 UPDATE 是自动提交的（锁只持有毫秒级），可以放心先收敛再过期过滤；
        // listValidPending 本身只读，不能在长事务里做这个 UPDATE
        commandPendingService.expireTimeoutOfSession(sessionId);
        List<PendingCommandVO> pending = commandPendingService.listValidPending(sessionId);
        log.info("查询待确认命令: sessionId={}, count={}", sessionId, pending.size());
        return ApiResponse.ok(pending);
    }

    @PostMapping("/{messageid}")
    public ApiResponse<String> Command_Confirmed(@PathVariable String messageid) {
        log.info("收到命令确认请求: messageId={}", messageid);
        return ApiResponse.ok(command_ConfirmedService.Command_Confirmed(messageid));
    }

    /**
     * 查询命令当前状态（前端兜底通道）
     * <p>
     * 为什么需要它：WebSocket 会话存在进程内存里，断线、重连、进程重启都会丢事件，
     * 前端只靠事件会出现"永远执行中"。这里按主键查一次，代价极低，作为最终一致性兜底。
     *
     * @param messageid 命令记录的消息 ID
     * @return 状态信息；记录不存在时返回空对象（status=null），前端按"状态未知"处理
     */
    @GetMapping("/{messageid}")
    public ApiResponse<CommandStatusVO> queryStatus(@PathVariable String messageid) {
        CommandStatusVO status = command_ConfirmedService.queryStatus(messageid);
        if (status == null) {
            log.info("查询命令状态: messageId={} 记录不存在", messageid);
            return ApiResponse.fail(404, "命令记录不存在");
        }
        return ApiResponse.ok(status);
    }
}
