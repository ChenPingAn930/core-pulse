package com.corepulse.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 停止烤机（FurMark）任务请求 DTO
 */
@Data
public class FurmarkStopDTO {

    /** 要停止的任务 ID */
    @NotNull(message = "taskId 不能为空")
    private Long taskId;
}
