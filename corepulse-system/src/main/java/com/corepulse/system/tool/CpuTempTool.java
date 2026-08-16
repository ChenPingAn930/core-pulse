package com.corepulse.system.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * CPU 温度监控工具
 * <p>
 * 启动 Core Temp 实时查看 CPU 温度与负载。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CpuTempTool implements SystemTool {

    private final ToolPathConfig toolPath;

    @Override
    public String toolName() {
        return "open_cpu_temp";
    }

    @Override
    public String toolDescription() {
        return "打开 CPU 温度监控工具（Core Temp），实时查看 CPU 温度与负载";
    }

    /**
     * 打开 CPU 温度监控
     *
     * @return 启动结果描述
     */
    @Tool(name = "openCpuTemp", description = "打开 CPU 温度监控工具（Core Temp），实时查看各核心温度与负载。")
    public String openCpuTemp() {
        log.info("调用工具: openCpuTemp，打开 CPU 温度监控");
        return ToolProcessRunner.startExe(toolPath.getCoreTempPath(), null, null);
    }
}
