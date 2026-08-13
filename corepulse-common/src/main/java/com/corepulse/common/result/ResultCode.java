package com.corepulse.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一错误码
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(0, "ok"),
    BAD_REQUEST(40001, "参数错误"),
    UNAUTHORIZED(40101, "未登录/凭证无效"),
    NOT_FOUND(40401, "资源不存在"),
    INTERNAL_ERROR(50001, "服务器内部错误"),
    LLM_ERROR(50002, "LLM 调用失败"),
    TOOL_ERROR(50003, "工具执行失败"),
    TASK_STATE_ERROR(50004, "任务状态不允许该操作");

    private final int code;
    private final String message;
}
