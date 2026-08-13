package com.corepulse.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 系统信息快照实体
 * <p>
 * 对应表：t_system_info，保存某次采集到的系统硬件与实时状态信息。
 */
@Data
@TableName("t_system_info")
public class SystemInfoSnapshot {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

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
    private BigDecimal cpuLoad;

    /** 内存使用率（百分比，如 72.0 表示 72%） */
    private BigDecimal memUsage;

    /** CPU 温度（摄氏度，如 56.0，硬件不支持时为 null） */
    private BigDecimal cpuTemp;

    /** 磁盘健康状态，枚举：HEALTHY / WARNING */
    private String diskHealth;

    /** 采集时间 */
    private LocalDateTime createdAt;
}
