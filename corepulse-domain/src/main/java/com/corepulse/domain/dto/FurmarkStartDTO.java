package com.corepulse.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 启动烤机（FurMark）任务请求 DTO
 */
@Data
public class FurmarkStartDTO {

    /** 测试时长（分钟），范围 1-1440，默认 30 */
    @Min(value = 1, message = "时长最小1分钟")
    @Max(value = 1440, message = "时长最大1440分钟")
    private Integer durationMin = 30;

    /** 关联会话 ID，可选 */
    private Long sessionId;
}
