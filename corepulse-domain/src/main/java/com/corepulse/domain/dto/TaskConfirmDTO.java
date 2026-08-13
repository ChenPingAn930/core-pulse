package com.corepulse.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 任务确认请求 DTO
 * <p>
 * 用于在任务处于 WAITING_CONFIRM 状态时，用户对危险操作进行确认或拒绝。
 */
@Data
public class TaskConfirmDTO {

    /** 是否确认执行：true 确认，false 拒绝 */
    @NotNull(message = "approved 不能为空")
    private Boolean approved;
}
