package com.corepulse.chat.controller;

import com.corepulse.chat.model.dto.CreateSessionRequest;
import com.corepulse.chat.model.vo.SessionVO;
import com.corepulse.chat.service.SessionService;
import com.corepulse.common.result.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 会话控制器
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    /**
     * 会话列表（分页）
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(sessionService.list(page, size));
    }

    /**
     * 会话消息历史（分页）
     */
    @GetMapping("/{sessionId}/messages")
    public ApiResponse<Map<String, Object>> messages(@PathVariable Long sessionId,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(sessionService.getMessages(sessionId, page, size));
    }

    /**
     * 新建会话
     */
    @PostMapping
    public ApiResponse<SessionVO> create(@Valid @RequestBody(required = false) CreateSessionRequest request) {
        String title = (request == null || request.getTitle() == null) ? "新会话" : request.getTitle();
        return ApiResponse.ok(sessionService.create(title));
    }
}
