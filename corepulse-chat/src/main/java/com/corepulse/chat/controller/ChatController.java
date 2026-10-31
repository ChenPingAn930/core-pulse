package com.corepulse.chat.controller;

import com.corepulse.chat.model.dto.ChatSendRequest;
import com.corepulse.chat.model.vo.ChatSendVO;
import com.corepulse.chat.service.SessionChatDispatcher;
import com.corepulse.common.result.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 聊天控制器
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    /**
     * 注入调度器而不是 ChatService：所有 LLM 推理必须经过会话锁，
     * 否则用户消息与后台播报会并发写同一个会话
     */
    private final SessionChatDispatcher chatDispatcher;

    /**
     * 发送消息 - AI 对话
     */
    @PostMapping("/send")
    public ApiResponse<ChatSendVO> send(@Valid @RequestBody ChatSendRequest request) {
        // source 是后端内部信任标记（决定是否豁免意图识别与仲裁），外部请求一律清空，
        // 否则调用方自己传一个 source 就能让后端跳过校验
        request.setSource(null);
        return ApiResponse.ok(chatDispatcher.send(request));
    }

    /**
     * 发送消息（别名路径 /message，兼容前端调用）
     */
    @PostMapping("/message")
    public ApiResponse<ChatSendVO> message(@Valid @RequestBody ChatSendRequest request) {
        request.setSource(null);
        return ApiResponse.ok(chatDispatcher.send(request));
    }
}
