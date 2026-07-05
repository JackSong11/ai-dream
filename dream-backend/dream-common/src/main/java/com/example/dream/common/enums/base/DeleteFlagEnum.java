package com.example.dream.common.enums.base;

import lombok.Getter;

/**
 * 逻辑删除字段 0:代表有效， 1:代表逻辑删除
 */
@Getter
public enum DeleteFlagEnum {

    VALID(0, "有效"),
    FAIL(1, "无效");

    DeleteFlagEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    private final Integer code;
    private final String desc;

}
