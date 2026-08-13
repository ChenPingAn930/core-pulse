package com.corepulse.common.result;

import lombok.Data;

/**
 * 统一响应结构
 */
@Data
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(ResultCode.SUCCESS.getCode());
        resp.setMessage(ResultCode.SUCCESS.getMessage());
        resp.setData(data);
        return resp;
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(code);
        resp.setMessage(message);
        return resp;
    }

    public static <T> ApiResponse<T> fail(ResultCode rc) {
        return fail(rc.getCode(), rc.getMessage());
    }
}
