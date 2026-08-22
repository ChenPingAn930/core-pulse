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

    /** CPU 温度监控（Core Temp） */
    private String coreTempPath = "./tool/处理器工具/CoreTemp/Core Temp.exe";

    /** CPU 信息（CPU-Z） */
    private String cpuzPath = "./tool/处理器工具/CPUZ/cpuz_x64.exe";

    /** 硬盘健康检测（smartmontools smartctl） */
    private String smartctlPath = "./tool/disk-tools/smartmontools/bin/smartctl.exe";

    /** 硬盘测速（CrystalDiskMark） */
    private String crystalDiskMarkPath = "./tool/disk-tools/CrystalDiskMark/DiskMark64.exe";

    

    /** VC++ 运行库离线安装包（64位） */
    private String vcRedistX64Path = "./tool/运行库/vc_redist.x64.exe";

    /** VC++ 运行库离线安装包（32位） */
    private String vcRedistX86Path = "./tool/运行库/vc_redist.x86.exe";
}
