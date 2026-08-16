package com.corepulse.system.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 硬盘测速工具
 * <p>
 * 启动 CrystalDiskMark 测试硬盘读写速度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiskBenchTool implements SystemTool {

    private final ToolPathConfig toolPath;

    @Override
    public String toolName() {
        return "bench_disk";
    }

    @Override
    public String toolDescription() {
        return "打开硬盘测速工具（CrystalDiskMark），测试硬盘读写速度";
    }

    /**
     * 测试硬盘速度
     *
     * @return 启动结果描述
     */
    @Tool(name = "benchDisk", description = "打开 CrystalDiskMark 工具，测试硬盘的顺序读写和随机读写速度。")
    public String benchDisk() {
        log.info("调用工具: benchDisk，测试硬盘读写速度");
        // CrystalDiskMark 写入测试需要管理员权限，使用提权方式启动（会弹 UAC 确认框）
        return ToolProcessRunner.startExeElevated(toolPath.getCrystalDiskMarkPath(), null);
    }
}
