package com.corepulse.system.tool;

import com.corepulse.domain.vo.SystemInfoVO;
import com.corepulse.system.service.SystemInfoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oshi.SystemInfo;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统信息查询工具
 * <p>
 * 复用 SystemInfoService 的 OSHI 采集逻辑，让 LLM 直接读取硬件配置、实时状态与磁盘空间，
 * 无需打开 GUI 程序即可获得数据用于诊断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemInfoTool implements SystemTool {

    private final SystemInfoService systemInfoService;
    private final ObjectMapper objectMapper;
    private final SystemInfo systemInfo = new SystemInfo();

    @Override
    public String toolName() {
        return "get_system_info";
    }

    @Override
    public String toolDescription() {
        return "获取电脑硬件配置与实时运行状态（CPU、内存、显卡、主板、磁盘、温度、负载等）";
    }

    /**
     * 获取完整硬件信息与实时状态
     *
     * @return JSON 格式的系统信息
     */
    @Tool(name = "getSystemInfo", description = "获取电脑的硬件配置与实时运行状态，包括操作系统、CPU、内存、显卡、主板、磁盘、CPU负载、内存占用、温度等。返回JSON。")
    public String getSystemInfo() {
        log.info("调用工具: getSystemInfo，开始采集硬件信息与实时状态");
        try {
            SystemInfoVO vo = systemInfoService.getSystemInfo();
            log.info("调用工具: getSystemInfo 完成，数据={}", objectMapper.writeValueAsString(vo));
            return objectMapper.writeValueAsString(vo);
        } catch (Exception e) {
            log.error("调用工具: getSystemInfo 失败", e);
            return "{\"error\":\"获取系统信息失败：" + e.getMessage() + "\"}";
        }
    }

    /**
     * 获取磁盘空间使用情况
     *
     * @return JSON 格式的磁盘分区信息
     */
    @Tool(name = "getDiskSpace", description = "获取电脑各磁盘分区的总容量、剩余空间和使用率。返回JSON。")
    public String getDiskSpace() {
        log.info("调用工具: getDiskSpace，开始查询磁盘空间");
        try {
            FileSystem fileSystem = systemInfo.getOperatingSystem().getFileSystem();
            List<Map<String, Object>> disks = new ArrayList<>();
            for (OSFileStore store : fileSystem.getFileStores()) {
                Map<String, Object> disk = new LinkedHashMap<>();
                disk.put("mount", store.getMount());
                disk.put("name", store.getName());
                disk.put("type", store.getType());
                long total = store.getTotalSpace();
                long usable = store.getUsableSpace();
                disk.put("totalGb", Math.round(total / (1024.0 * 1024 * 1024)));
                disk.put("usableGb", Math.round(usable / (1024.0 * 1024 * 1024)));
                disk.put("usedPercent", total > 0 ? Math.round((total - usable) * 100.0 / total) : 0);
                disks.add(disk);
            }
            log.info("调用工具: getDiskSpace 完成，共 {} 个分区", disks.size());
            return objectMapper.writeValueAsString(disks);
        } catch (Exception e) {
            log.error("调用工具: getDiskSpace 失败", e);
            return "{\"error\":\"获取磁盘空间失败：" + e.getMessage() + "\"}";
        }
    }
}
