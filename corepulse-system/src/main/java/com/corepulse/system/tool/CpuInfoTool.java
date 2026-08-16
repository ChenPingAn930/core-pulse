package com.corepulse.system.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * CPU 信息工具
 * <p>
 * 启动 CPU-Z 查看 CPU 详细信息（型号、主频、缓存、主板等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CpuInfoTool implements SystemTool {

    private final ToolPathConfig toolPath;

    @Override
    public String toolName() {
        return "open_cpu_info";
    }

    @Override
    public String toolDescription() {
        return "打开 CPU-Z 查看 CPU 详细信息（型号、主频、缓存、主板等）";
    }

    /**
     * 打开 CPU 信息
     *
     * @return 启动结果描述
     */
    @Tool(name = "openCpuInfo", description = "打开 CPU-Z 工具，查看 CPU 型号、主频、缓存、主板等详细信息。")
    public String openCpuInfo() {
        log.info("调用工具: openCpuInfo，打开 CPU 信息查看器");
        return ToolProcessRunner.startExe(toolPath.getCpuzPath(), null, null);
    }
}
