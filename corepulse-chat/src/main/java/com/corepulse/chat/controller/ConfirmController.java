package com.corepulse.chat.controller;

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
}
