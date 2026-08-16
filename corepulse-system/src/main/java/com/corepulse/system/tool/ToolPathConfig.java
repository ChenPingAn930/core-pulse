package com.corepulse.system.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工具路径配置
 * <p>
 * 集中管理各工具的 exe 路径，可通过 application.yml 的 corepulse.tool.* 覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "corepulse.tool")
public class ToolPathConfig {

    /** 显卡烤机（FurMark） */
    private String furmarkPath = "./tool/烤鸡工具/FurMark/FurMark.exe";

    /** 内存测试（MemTest64） */
    private String memtestPath = "./tool/内存工具/memtest64/MemTest64.exe";

    /** 内存压力测试（Sysinternals Testlimit） */
    private String testlimitPath = "./tool/内存工具/Testlimit/Testlimit64.exe";

    /** CPU 压力测试（Prime95） */
    private String prime95Path = "./tool/处理器工具/Prime95/prime95.exe";

    /** CPU 温度监控（Core Temp） */
    private String coreTempPath = "./tool/处理器工具/CoreTemp/Core Temp x64.exe";

    /** CPU 信息（CPU-Z） */
    private String cpuzPath = "./tool/处理器工具/CPUZ/cpuz_x64.exe";

    /** 硬盘健康检测（smartmontools smartctl） */
    private String smartctlPath = "./tool/硬盘工具/smartmontools/bin/smartctl.exe";

    /** 硬盘测速（CrystalDiskMark） */
    private String crystalDiskMarkPath = "./tool/硬盘工具/CrystalDiskMark/DiskMark64S.exe";

    /** 综合检测（AIDA64） */
    private String aida64Path = "./tool/综合检测/AIDA64/aida64.exe";

    /** GPU 信息（GPU-Z） */
    private String gpuzPath = "./tool/显卡工具/GPUZ/GPU-Z.exe";
}
