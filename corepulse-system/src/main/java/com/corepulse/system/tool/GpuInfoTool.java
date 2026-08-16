package com.corepulse.system.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * GPU 信息工具
 * <p>
 * 启动 GPU-Z 查看显卡详细信息（型号、显存、驱动、温度等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GpuInfoTool implements SystemTool {

    private final ToolPathConfig toolPath;

    @Override
    public String toolName() {
        return "open_gpu_info";
    }

    @Override
    public String toolDescription() {
        return "打开 GPU-Z 查看显卡详细信息（型号、显存、驱动、温度等）";
    }

    /**
     * 打开 GPU 信息
     *
     * @return 启动结果描述
     */
    @Tool(name = "openGpuInfo", description = "打开 GPU-Z 工具，查看显卡型号、显存、驱动版本、温度等详细信息。")
    public String openGpuInfo() {
        log.info("调用工具: openGpuInfo，打开显卡信息查看器");
        return ToolProcessRunner.startExe(toolPath.getGpuzPath(), null, null);
    }
}
