package com.example.dream.common.enums.document;

import lombok.Getter;

/**
 * 文档状态枚举，对应 RagFlow common.constants.StatusEnum。
 *
 * <p>标识文档是否有效可用，落库到 kb_document.status 字段（varchar）。</p>
 *
 * @author dream
 */
@Getter
public enum DocStatusEnum {

    /**
     * 无效（对应 RagFlow StatusEnum.INVALID = "0"）
     */
    INVALID("0"),

    /**
     * 有效（对应 RagFlow StatusEnum.VALID = "1"）
     */
    VALID("1");

    /**
     * 状态编码
     */
    private final String value;

    DocStatusEnum(String value) {
        this.value = value;
    }
}