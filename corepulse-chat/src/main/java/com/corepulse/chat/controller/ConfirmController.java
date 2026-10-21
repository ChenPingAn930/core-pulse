package com.corepulse.chat.controller;

import com.corepulse.chat.model.vo.CommandStatusVO;
import com.corepulse.chat.service.Command_ConfirmedService;
import com.corepulse.common.result.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/Confirmed")
public class ConfirmController {

    @Autowired
    Command_ConfirmedService command_ConfirmedService;

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
