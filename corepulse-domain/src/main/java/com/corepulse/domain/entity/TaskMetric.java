package com.corepulse.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 任务实时指标实体
 * <p>
 * 对应表：t_task_metric，保存任务执行过程中的实时硬件指标（如烤机时的温度、帧率等）。
 */
@Data
@TableName("t_task_metric")
public class TaskMetric {

    /** 指标记录主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务 ID */
    private Long taskId;

    /** CPU 温度（摄氏度） */
    private BigDecimal cpuTemp;

    /** GPU 温度（摄氏度） */
    private BigDecimal gpuTemp;

    /** GPU 帧率（FPS） */
    private Integer gpuFps;

    /** GPU 负载（百分比） */
    private BigDecimal gpuLoad;

    /** 记录时间 */
    private LocalDateTime recordTime;
}
