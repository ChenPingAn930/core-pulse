package com.corepulse.system.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 综合检测工具
 * <p>
 * 启动 AIDA64 进行系统综合信息检测与稳定性测试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Aida64Tool implements SystemTool {

    private final ToolPathConfig toolPath;

    @Override
    public String toolName() {
        return "open_aida64";
    }

    @Override
    public String toolDescription() {
        return "打开 AIDA64 综合检测工具，查看系统硬件信息与进行稳定性测试";
    }

    /**
     * 打开 AIDA64 综合检测
     *
     * @return 启动结果描述
     */
    @Tool(name = "openAida64", description = "打开 AIDA64 综合检测工具，查看系统详细硬件信息、传感器数据，并可进行稳定性测试。")
    public String openAida64() {
        log.info("调用工具: openAida64，打开综合检测工具");
        return ToolProcessRunner.startExe(toolPath.getAida64Path(), null, null);
    }
}
