package com.corepulse.system.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 硬盘健康检测工具（smartctl / smartmontools）
 * <p>
 * 通过命令行工具 smartctl 读取硬盘 S.M.A.R.T. 健康数据（温度、通电时间、错误计数等），
 * 返回纯文本给 LLM 解析，LLM 据此判断硬盘健康状态。
 * <p>
 * 相比 GUI 工具（CrystalDiskInfo），smartctl 是命令行工具，LLM 能直接读取其输出，
 * 实现真正的"读懂硬盘健康数据"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiskSmartTool implements SystemTool {

    /** 输出最大长度（避免撑爆 LLM 上下文） */
    private static final int MAX_OUTPUT_LENGTH = 4000;

    private final ToolPathConfig toolPath;

    @Override
    public String toolName() {
        return "check_disk_health";
    }

    @Override
    public String toolDescription() {
        return "检测硬盘健康状态：列出所有磁盘，并读取指定磁盘的 S.M.A.R.T. 健康数据（温度、通电时间、重分配扇区、错误计数等）";
    }

    /**
     * 列出电脑上的所有磁盘设备
     *
     * @return 磁盘设备列表（设备标识 + 类型）
     */
    @Tool(name = "scanDiskDevices", description = "列出电脑上所有磁盘设备（含设备标识，如 /dev/sda）。返回JSON或文本列表。")
    public String scanDiskDevices() {
        log.info("调用工具: scanDiskDevices，扫描磁盘设备");
        return runSmartctl(List.of("--scan"));
    }

    /**
     * 读取指定磁盘的 S.M.A.R.T. 健康状态
     *
     * @param device 磁盘设备标识，如 /dev/sda（可先调用 scanDiskDevices 获取）
     * @return S.M.A.R.T. 健康数据文本（含健康状态、温度、通电时间、关键属性等）
     */
    @Tool(name = "checkDiskHealth", description = "读取指定磁盘的完整 S.M.A.R.T. 健康数据。device 为磁盘设备标识（如 /dev/sda，先调用 scanDiskDevices 获取）。返回文本，含健康状态(PASSED/FAILED)、温度、通电时间、重分配扇区、不可纠正错误等关键属性。")
    public String checkDiskHealth(
            @ToolParam(description = "磁盘设备标识，如 /dev/sda") String device) {
        log.info("调用工具: checkDiskHealth，读取磁盘 S.M.A.R.T. 健康数据，device={}", device);
        if (device == null || device.isBlank()) {
            return "请先调用 scanDiskDevices 获取磁盘设备标识，再读取健康数据。";
        }
        return runSmartctl(List.of("-a", device.trim()));
    }

    /**
     * 执行 smartctl 命令并返回输出
     *
     * @param args smartctl 命令行参数
     * @return 命令输出（截断后）
     */
    private String runSmartctl(List<String> args) {
        File exe = new File(toolPath.getSmartctlPath());
        if (!exe.exists()) {
            log.error("smartctl 不存在: {}", exe.getAbsolutePath());
            return "检测失败：未找到 smartctl 工具，路径=" + exe.getAbsolutePath()
                    + "。请确认 smartmontools 已安装到 tool/disk-tools/smartmontools。";
        }
        try {
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(exe.getAbsolutePath());
            command.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            process.waitFor();
            String result = output.toString().trim();
            log.info("smartctl 执行完成: args={}, 输出长度={}", args, result.length());
            return truncate(result, MAX_OUTPUT_LENGTH);
        } catch (Exception e) {
            log.error("smartctl 执行失败: args={}", args, e);
            return "检测失败：" + e.getMessage();
        }
    }

    /** 截断长文本 */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n...(输出过长已截断)";
    }
}
