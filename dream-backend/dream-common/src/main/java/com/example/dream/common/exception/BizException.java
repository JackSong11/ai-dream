package com.example.dream.common.exception;

import com.example.dream.common.enums.base.ResCodeEnum;
import lombok.Getter;

/**
 * 通用业务异常
 *
 * @author dream
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private final String code;

    public BizException(String message) {
        super(message);
        this.code = ResCodeEnum.FAIL.getCode();
    }

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(ResCodeEnum resCodeEnum) {
        super(resCodeEnum.getDesc());
        this.code = resCodeEnum.getCode();
    }

    public BizException(ResCodeEnum resCodeEnum, String message) {
        super(message);
        this.code = resCodeEnum.getCode();
    }
}