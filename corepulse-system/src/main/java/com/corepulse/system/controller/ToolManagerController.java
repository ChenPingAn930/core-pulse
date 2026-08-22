package com.corepulse.system.controller;

import com.corepulse.common.result.ApiResponse;
import com.corepulse.system.service.ToolManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本地维修工具准备接口。
 */
@RestController
@RequestMapping("/api/tools/manager")
@RequiredArgsConstructor
public class ToolManagerController {

    private final ToolManagerService toolManagerService;

    @GetMapping("/status")
    public ApiResponse<?> status() {
        return ApiResponse.ok(toolManagerService.getStatuses());
    }

    @PostMapping("/prepare")
    public ApiResponse<?> prepare(@RequestBody(required = false) PrepareRequest request) {
        boolean confirmed = request != null && request.confirmed();
        List<String> toolIds = request == null ? List.of() : request.toolIds();
        return ApiResponse.ok(toolManagerService.prepare(confirmed, toolIds));
    }

    @PostMapping("/install")
    public ApiResponse<?> install(@RequestBody(required = false) InstallRequest request) {
        String arch = request == null ? null : request.arch();
        boolean confirmed = request != null && request.confirmed();
        return ApiResponse.ok(toolManagerService.install(arch, confirmed));
    }

    public record PrepareRequest(boolean confirmed, List<String> toolIds) {
    }

    public record InstallRequest(String arch, boolean confirmed) {
    }
}
