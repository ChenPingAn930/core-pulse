package com.corepulse.common.exception;

import com.corepulse.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(ResultCode rc) {
        this(rc.getCode(), rc.getMessage());
    }

    public BizException(ResultCode rc, String message) {
        this(rc.getCode(), message);
    }
}
