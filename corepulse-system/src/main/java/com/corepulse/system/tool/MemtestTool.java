package com.corepulse.system.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 内存测试工具
 * <p>
 * 启动 MemTest64 进行内存压力测试（GUI 程序，用户可自行查看和停止）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemtestTool implements SystemTool {

    private final ToolPathConfig toolPath;

    @Override
    public String toolName() {
        return "run_memtest";
    }

    @Override
    public String toolDescription() {
        return "启动内存压力测试工具（MemTest64），检测内存稳定性";
    }

    /**
     * 启动内存压力测试
     *
     * @return 启动结果描述
     */
    @Tool(name = "runMemTest", description = "启动内存压力测试工具（MemTest64），用于检测内存是否存在故障。")
    public String startMemtest() {
        log.info("调用工具: runMemTest，启动内存压力测试");
        return ToolProcessRunner.startExe(toolPath.getMemtestPath(), null, null);
    }
}
