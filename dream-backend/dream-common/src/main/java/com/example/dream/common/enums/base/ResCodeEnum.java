package com.example.dream.common.enums.base;

import lombok.Getter;

/**
 * ResCodeEnum
 *
 * @author songzhiquan1
 * @date 2024-11-29 17:39
 */

@Getter
public enum ResCodeEnum {
    SUCCESS("200", "成功"),
    FAIL("999", "失败"),
    BAD_REQUEST("400", "错误的请求"),
    UNAUTHORIZED("401", "认证失败"),
    SERVER_ERROR("500", "系统异常"),
    DATA_ERROR("600", "数据错误"),
    DATA_NOT_EXIST("601", "数据不存在"),
    PARAMETER_ERROR("700", "参数错误"),
    ;


    ResCodeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    private final String desc;
    private final String code;

    public static String getDesc(String key) {
        for (ResCodeEnum pm : ResCodeEnum.values()) {
            if (pm.getCode().equals(key)) {
                return pm.getDesc();
            }
        }
        return null;
    }

    public static String getCode(String value) {
        for (ResCodeEnum pm : ResCodeEnum.values()) {
            if (pm.getDesc().equals(value)) {
                return pm.getCode();
            }
        }
        return null;
    }
}
