package com.example.dream.common.enums.document;

import lombok.Getter;

/**
 * 文档/任务运行状态枚举，对应 RagFlow common.constants.TaskStatus。
 *
 * @author dream
 */
@Getter
public enum TaskStatusEnum {

    /**
     * 未开始（对应 RagFlow TaskStatus.UNSTART = "0"）
     */
    UNSTART(0),

    /**
     * 运行中（对应 RagFlow TaskStatus.RUNNING = "1"）
     */
    RUNNING(1),

    /**
     * 已取消（对应 RagFlow TaskStatus.CANCEL = "2"）
     */
    CANCEL(2),

    /**
     * 已完成（对应 RagFlow TaskStatus.DONE = "3"）
     */
    DONE(3),

    /**
     * 失败（对应 RagFlow TaskStatus.FAIL = "4"）
     */
    FAIL(4);

    /**
     * 状态编码
     */
    private final int value;

    TaskStatusEnum(int value) {
        this.value = value;
    }
}