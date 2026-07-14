package com.example.dream.web.vo.ai;

import lombok.Data;

/**
 * 模型信息 VO（对前端展示，不含密钥等敏感字段）。
 *
 * @author dream
 */
@Data
public class ModelInfoVO {

    /**
     * 模型标识（切换时使用）。
     */
    private String modelKey;

    /**
     * 模型展示名称。
     */
    private String name;

    /**
     * 底层真实模型名。
     */
    private String model;

    /**
     * 是否为当前默认模型。
     */
    private boolean current;
}