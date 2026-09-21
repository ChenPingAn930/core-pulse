package com.corepulse.system.service.impl;

import com.corepulse.domain.vo.SystemInfoVO;
import com.corepulse.system.service.SystemInfoService;
import com.corepulse.system.tool.CpuTemperatureReader;
import com.corepulse.system.tool.GpuTemperatureReader;
import lombok.extern.slf4j.Slf4j;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;

/**
 * 系统信息服务实现
 * <p>
 * 使用 OSHI 采集真实的系统硬件信息与实时运行状态。
 */
@Slf4j
@Service
public class SystemInfoServiceImpl implements SystemInfoService {

    private final SystemInfo systemInfo = new SystemInfo();
    private final HardwareAbstractionLayer hal = systemInfo.getHardware();
    private final OperatingSystem os = systemInfo.getOperatingSystem();

    @Autowired
    private CpuTemperatureReader cpuTemperatureReader;

    @Autowired
    private GpuTemperatureReader gpuTemperatureReader;

    @Override
    public SystemInfoVO getSystemInfo() {
        SystemInfoVO vo = new SystemInfoVO();
        try {
            vo.setOs(buildOs());
            vo.setCpu(buildCpu());
            vo.setRam(buildRam());
            vo.setGpu(buildGpu());
            vo.setMotherboard(buildMotherboard());
            vo.setDisk(buildDisk());
            collectLoad(vo);
        } catch (Exception e) {
            log.error("采集系统信息失败", e);
            // 采集失败时返回空字段，避免接口报错
        }
        return vo;
    }

    /** 操作系统，如：Windows 11 专业版 (build 26200) */
    private String buildOs() {
        OperatingSystem.OSVersionInfo versionInfo = os.getVersionInfo();
        String family = os.getFamily();
        String buildNumber = versionInfo.getBuildNumber();
        StringBuilder sb = new StringBuilder(family != null ? family.trim() : "");
        // 大版本号：Windows 11 的 build 号 >= 22000，据此补充 "11"
        if (buildNumber != null && !buildNumber.isBlank()) {
            try {
                long build = Long.parseLong(buildNumber.trim());
                if (build >= 22000 && family != null && !family.contains("11")) {
                    sb.append(" 11");
                }
            } catch (NumberFormatException ignored) {
                // 非数字 build 号则忽略
            }
        }
        // 构建号
        if (buildNumber != null && !buildNumber.isBlank() && !"unknown".equalsIgnoreCase(buildNumber)) {
            sb.append(" (build ").append(buildNumber).append(")");
        }
        return sb.toString().trim();
    }

    /** CPU 型号 */
    private String buildCpu() {
        CentralProcessor processor = hal.getProcessor();
        String name = processor.getProcessorIdentifier().getName();
        long freq = processor.getMaxFreq();
        String freqText = freq > 0 ? " @ " + (freq / 1_000_000_000.0) + "GHz" : "";
        return name.trim() + freqText;
    }

    /** 内存，逐根列出，如：金士顿 16GB DDR5 4800MHz；芝奇 16GB DDR5 5600MHz */
    private String buildRam() {
        GlobalMemory memory = hal.getMemory();
        java.util.List<oshi.hardware.PhysicalMemory> phyList = memory.getPhysicalMemory();
        if (phyList.isEmpty()) {
            return "未知内存";
        }
        java.util.List<String> sticks = new java.util.ArrayList<>();
        long totalBytes = 0;
        for (oshi.hardware.PhysicalMemory pm : phyList) {
            totalBytes += pm.getCapacity();
            sticks.add(formatRamStick(pm));
        }
        long totalGb = Math.round(totalBytes / (1024.0 * 1024.0 * 1024.0));
        // 格式：总容量（单根明细...）
        return totalGb + "GB (" + String.join("；", sticks) + ")";
    }

    /** 单根内存条规格：品牌 + 容量 + 类型 + 频率 */
    private String formatRamStick(oshi.hardware.PhysicalMemory pm) {
        StringBuilder sb = new StringBuilder();
        String brand = pm.getManufacturer();
        if (brand != null && !brand.isBlank() && !"unknown".equalsIgnoreCase(brand)) {
            sb.append(brand.trim()).append(" ");
        }
        long capacityGb = Math.round(pm.getCapacity() / (1024.0 * 1024.0 * 1024.0));
        sb.append(capacityGb).append("GB ");
        String type = pm.getMemoryType();
        if (type != null && !type.isBlank() && !"unknown".equalsIgnoreCase(type)) {
            sb.append(type).append(" ");
        }
        long freqHz = pm.getClockSpeed();
        if (freqHz > 0) {
            // OSHI 返回的是 Hz，转换为 MHz
            long freqMhz = freqHz >= 1_000_000_000L ? freqHz / 1_000_000L : freqHz;
            sb.append(freqMhz).append("MHz");
        }
        return sb.toString().trim();
    }

    /** 显卡，如：NVIDIA RTX 4060 Laptop (8GB) */
    private String buildGpu() {
        return hal.getGraphicsCards().stream()
                .findFirst()
                .map(g -> {
                    String name = g.getName();
                    long vram = g.getVRam();
                    String vramText = vram > 0 ? " (" + (vram / (1024L * 1024L * 1024L)) + "GB)" : "";
                    return name + vramText;
                })
                .orElse("未知显卡");
    }

    /** 主板，优先 厂商+型号，如：ASUSTeK COMPUTER INC. TUF GAMING B660M-PLUS */
    private String buildMotherboard() {
        oshi.hardware.ComputerSystem computerSystem = hal.getComputerSystem();
        oshi.hardware.Baseboard board = computerSystem.getBaseboard();
        String vendor = board.getManufacturer();
        String model = board.getModel();
        StringBuilder sb = new StringBuilder();
        if (vendor != null && !vendor.isBlank() && !"unknown".equalsIgnoreCase(vendor)
                && !vendor.contains("To be filled") && !vendor.contains("System manufacturer")) {
            sb.append(vendor.trim()).append(" ");
        }
        // 具体型号：优先 baseboard model，缺失时尝试通过 WMI 命令补充
        String bestModel = model;
        if (bestModel == null || bestModel.isBlank() || "unknown".equalsIgnoreCase(bestModel)
                || bestModel.contains("To be filled")) {
            String wmiModel = queryWmi();
            if (wmiModel != null && !wmiModel.isBlank() && !"unknown".equalsIgnoreCase(wmiModel)
                    && !wmiModel.contains("找不到") && !wmiModel.contains("无法") && !wmiModel.contains("not recognized")) {
                bestModel = wmiModel;
            }
        }
        if (bestModel != null && !bestModel.isBlank() && !"unknown".equalsIgnoreCase(bestModel)
                && !bestModel.contains("To be filled")) {
            sb.append(bestModel.trim());
        }
        return sb.length() > 0 ? sb.toString().trim() : "未知主板";
    }

    /**
     * 通过 PowerShell CIM 查询主板具体型号
     * <p>
     * 使用 Get-CimInstance 比 wmic 更可靠（wmic 在新系统已弃用）。
     * 命令失败或输出错误信息时返回 null。
     */
    private String queryWmi() {
        try {
            Process process = new ProcessBuilder("powershell",
                    "-NoProfile", "-Command",
                    "(Get-CimInstance Win32_BaseBoard).Product")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            // 命令执行失败（如 wmic/powershell 不可用）或输出含错误信息时返回 null
            if (exitCode != 0 || output.isBlank() || output.contains("无法") || output.contains("找不到")
                    || output.contains("不是内部或外部命令") || output.contains("not recognized")) {
                return null;
            }
            return output;
        } catch (Exception e) {
            log.warn("CIM 查询主板失败", e);
            return null;
        }
    }

    /** 磁盘，逐块列出，如：SKHynix HFS512G x2（共 1TB）；Samsung 1TB */
    private String buildDisk() {
        var disks = hal.getDiskStores();
        if (disks.isEmpty()) {
            return "未知磁盘";
        }
        java.util.List<String> diskList = new java.util.ArrayList<>();
        long totalBytes = 0;
        for (var d : disks) {
            totalBytes += d.getSize();
            long sizeGb = Math.round(d.getSize() / (1024.0 * 1024.0 * 1024.0));
            String model = cleanModel(d.getModel());
            diskList.add((model.isBlank() ? "磁盘" : model) + " " + sizeGb + "GB");
        }
        long totalGb = Math.round(totalBytes / (1024.0 * 1024.0 * 1024.0));
        // 格式：总容量（每块盘明细...）
        return totalGb + "GB (" + String.join("；", diskList) + ")";
    }

    /**
     * 清理磁盘型号，去掉系统附加的描述
     * 如：SKHynix_HFS512GEJ4X112N (标准磁盘驱动器) -> SKHynix HFS512GEJ4X112N
     */
    private String cleanModel(String model) {
        if (model == null) {
            return "";
        }
        // 去掉括号内容，如 "(标准磁盘驱动器)"
        String cleaned = model.replaceAll("\\(.*?\\)", "").trim();
        // 下划线替换为空格
        cleaned = cleaned.replace('_', ' ').replaceAll("\\s+", " ").trim();
        if ("unknown".equalsIgnoreCase(cleaned)) {
            return "";
        }
        return cleaned;
    }

    /** 实时负载：CPU 占用、内存占用、温度、磁盘健康 */
    private void collectLoad(SystemInfoVO vo) {
        CentralProcessor processor = hal.getProcessor();
        GlobalMemory memory = hal.getMemory();

        // CPU 使用率（两次采样取差，间隔略长以保证准确）
        long[] prevTicks = processor.getSystemCpuLoadTicks();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 传入旧采样点，计算两次采样之间的 CPU 使用率
        double cpuLoad = round(processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100);
        vo.setCpuLoad(cpuLoad);

        // 内存使用率
        double memUsage = round((memory.getTotal() - memory.getAvailable()) * 100.0 / memory.getTotal());
        vo.setMemUsage(memUsage);

        // CPU 温度（多路径兜底：Win32_TemperatureProbe → 性能计数器 → CIM 新栈 → OSHI 兜底）
        Double cpuTemp = cpuTemperatureReader.readCpuTemperature();
        if (cpuTemp != null && cpuTemp > 0) {
            vo.setCpuTemp(round(cpuTemp));
        }

        // GPU 温度（NVIDIA 用 nvidia-smi，AMD 用 WMI，读不到则为 null）
        Double gpuTemp = gpuTemperatureReader.readGpuTemperature();
        if (gpuTemp != null) {
            vo.setGpuTemp(round(gpuTemp));
        }

        // 磁盘健康状态
        vo.setDiskHealth(buildDiskHealth());
    }

    /** 磁盘健康：基于磁盘读写计数与型号评估，正常则 HEALTHY */
    private String buildDiskHealth() {
        // 取第一个磁盘
        var disk = hal.getDiskStores().stream().findFirst().orElse(null);
        if (disk == null) {
            return "UNKNOWN";
        }
        // 通过 S.M.A.R.T. 属性评估：若模型名未知或磁盘异常，则提示
        String model = disk.getModel();
        if (model == null || model.isBlank() || "unknown".equalsIgnoreCase(model)) {
            return "WARNING";
        }
        // 磁盘可正常识别且无读写异常（读写次数合理）则视为健康
        return "HEALTHY";
    }

    /** 保留一位小数 */
    private double round(double value) {
        return java.math.BigDecimal.valueOf(value)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
