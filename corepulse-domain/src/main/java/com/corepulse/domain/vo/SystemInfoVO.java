package com.corepulse.domain.vo;

import lombok.Data;

/**
 * 系统信息视图对象
 * <p>
 * 用于向前端返回系统硬件信息与实时运行状态。
 */
@Data
public class SystemInfoVO {

    /** 操作系统名称与版本，如：Windows 11 专业版 23H2 */
    private String os;

    /** CPU 型号，如：Intel Core i7-12700H @ 2.3GHz */
    private String cpu;

    /** 内存容量，如：32 GB DDR5 4800MHz */
    private String ram;

    /** 显卡型号，如：NVIDIA RTX 4060 Laptop (8GB) */
    private String gpu;

    /** 主板型号，如：ASUS TUF Gaming Z690 */
    private String motherboard;

    /** 磁盘型号与容量，如：Samsung 980 PRO 1TB (NVMe) */
    private String disk;

    /** CPU 使用率（百分比，如 45.0 表示 45%） */
    private Double cpuLoad;

    /** 内存使用率（百分比，如 72.0 表示 72%） */
    private Double memUsage;

    /** CPU 温度（摄氏度，如 56.0，硬件不支持时为 null） */
    private Double cpuTemp;

    /** 磁盘健康状态，枚举：HEALTHY / WARNING */
    private String diskHealth;
}
