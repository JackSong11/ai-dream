package com.example.dream.service.core.ai.config;

import lombok.Data;

/**
 * 单个模型的配置定义。
 * <p>
 * 模型挂在 {@link ProviderProperties} 下，共享供应商的 baseUrl / apiKey。
 * 因此同一 URL 下切换不同模型名时，只需在 provider.models 中追加
 * key + model + name 即可，URL / 密钥无需重复配置。
 * <p>
 * baseUrl / apiKey 为可选覆盖项：若某个模型需要走不同网关或密钥，
 * 可在模型级单独指定，留空则继承供应商配置。
 *
 * @author dream
 */
@Data
public class ModelProperties {

    /**
     * 模型唯一标识（业务侧切换时使用的 key），全局唯一。
     */
    private String key;

    /**
     * 模型展示名称，用于前端下拉展示。
     */
    private String name;

    /**
     * 底层真实模型名，如：Qwen3-235B-A22B、deepseek-chat。
     * 同一供应商下切换的核心字段。
     */
    private String model;

    /**
     * 采样温度。留空则继承供应商级 temperature。
     */
    private Double temperature;

    /**
     * 单次回复最大 token 数，null 表示使用服务端默认。
     */
    private Integer maxTokens;

    /**
     * 可选：覆盖供应商 baseUrl（一般不填）。
     */
    private String baseUrl;

    /**
     * 可选：覆盖供应商 apiKey（一般不填）。
     */
    private String apiKey;

    /**
     * 是否为默认模型。未显式指定 modelKey 时使用该模型。
     */
    private boolean primary = false;
}