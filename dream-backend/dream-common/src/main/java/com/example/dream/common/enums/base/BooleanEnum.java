package com.example.dream.common.enums.base;


import lombok.Getter;

@Getter
public enum BooleanEnum {

    FALSE(0, false),
    TRUE(1, true);

    BooleanEnum(Integer code, Boolean value) {
        this.code = code;
        this.value = value;
    }

    private final Integer code;
    private final Boolean value;
}
