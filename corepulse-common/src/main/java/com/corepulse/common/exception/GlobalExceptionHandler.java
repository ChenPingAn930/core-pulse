package com.corepulse.common.exception;

import com.corepulse.common.result.ApiResponse;
import com.corepulse.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
//全局异常处理器
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError == null ? "参数错误" : fieldError.getField() + " " + fieldError.getDefaultMessage();
        return ApiResponse.fail(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleNotReadable(HttpMessageNotReadableException e) {
        String msg = e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage();
        return ApiResponse.fail(ResultCode.BAD_REQUEST.getCode(), "请求体格式错误: " + msg);
    }

    /**
     * 请求路径不存在（既没有匹配的 Controller，也不是静态资源）
     * <p>
     * Spring 6.1 起，这类请求会抛 NoResourceFoundException。如果不单独处理，
     * 它会落到下面的 Exception 兜底，被记成"系统异常"并打印完整堆栈——
     * 语义上只是 404，却会刷满 ERROR 日志（前端调用了尚未实现的接口时尤其明显）。
     * 这里按 WARN 记录且不打堆栈，返回明确的 404 业务码。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("请求的资源不存在: path={}", e.getResourcePath());
        return ApiResponse.fail(ResultCode.NOT_FOUND.getCode(),
                "接口不存在: " + e.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return ApiResponse.fail(ResultCode.INTERNAL_ERROR);
    }
}
