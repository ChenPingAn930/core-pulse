package com.corepulse.system.controller;

import com.corepulse.common.result.ApiResponse;
import com.corepulse.domain.vo.SystemInfoVO;
import com.corepulse.system.service.SystemInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统信息控制器
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemInfoService systemInfoService;

    /**
     * 获取硬件信息与实时状态
     */
    @GetMapping("/info")
    public ApiResponse<SystemInfoVO> info() {
        return ApiResponse.ok(systemInfoService.getSystemInfo());
    }
}
