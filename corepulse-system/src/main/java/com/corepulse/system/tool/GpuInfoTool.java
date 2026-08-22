package com.corepulse.system.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * GPU 信息工具
 * <p>
 * 通过 {@code nvidia-smi}（NVIDIA 驱动自带）读取显卡详细信息（型号、温度、利用率、显存、驱动等），
 * 无需额外安装 GPU-Z。AMD 显卡暂不支持自动读取，返回提示信息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GpuInfoTool implements SystemTool {

    private final GpuTemperatureReader gpuTemperatureReader;

    @Override
    public String toolName() {
        return "open_gpu_info";
    }

    @Override
    public String toolDescription() {
        return "读取显卡详细信息（型号、温度、利用率、显存、驱动版本等）";
    }

    /**
     * 读取 GPU 信息
     *
     * @return GPU 信息描述
     */
    @Tool(name = "openGpuInfo", description = "读取显卡型号、温度、利用率、显存、驱动版本等详细信息。")
    public String openGpuInfo() {
        log.info("调用工具: openGpuInfo，读取显卡信息");
        // 优先尝试 nvidia-smi（NVIDIA 显卡）
        String nvidiaInfo = formatNvidiaInfo();
        if (nvidiaInfo != null) {
            return nvidiaInfo;
        }
        // 非 NVIDIA 显卡，尝试读取温度（AMD 可能不支持）
        Double temp = gpuTemperatureReader.readGpuTemperature();
        if (temp != null) {
            return "显卡温度: " + temp + "℃（AMD 显卡，仅能读取温度）";
        }
        return "无法读取显卡信息：当前显卡不是 NVIDIA（nvidia-smi 不可用），且 AMD 温度读取不受支持。";
    }

    /**
     * 通过 nvidia-smi 查询显卡信息。
     */
    private String queryNvidiaInfo() {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "nvidia-smi",
                    "--query-gpu=name,temperature.gpu,utilization.gpu,memory.total,memory.used,driver_version",
                    "--format=csv,noheader");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return output.toString().trim();
        } catch (Exception e) {
            log.debug("nvidia-smi 查询失败（可能非 NVIDIA 显卡）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 nvidia-smi 输出为可读文本。
     */
    private String formatNvidiaInfo() {
        String raw = queryNvidiaInfo();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // 输出形如 "NVIDIA GeForce RTX 2080, 55, 14 %, 8192 MiB, 1552 MiB, 610.47"
        String[] parts = raw.split(",");
        if (parts.length < 6) {
            return raw;
        }
        String name = parts[0].trim();
        String temp = parts[1].trim();
        String util = parts[2].trim();
        String memTotal = parts[3].trim();
        String memUsed = parts[4].trim();
        String driver = parts[5].trim();
        return String.format(
                "显卡型号: %s\n温度: %s ℃\n利用率: %s\n显存: %s / %s\n驱动版本: %s",
                name, temp, util, memUsed, memTotal, driver);
    }
}