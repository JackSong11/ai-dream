package com.example.dream.service.core.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 多模型总配置，绑定 application.yml 中 dream.ai 前缀。
 * <p>
 * 采用「供应商 → 模型」两层结构：同一 URL / 密钥的多个模型
 * 归属于一个 provider，共享连接参数。新增同网关的模型
 * 只需在对应 provider.models 下追加一项，无需改动任何 Java 代码。
 *
 * @author dream
 */
@Data
@ConfigurationProperties(prefix = "dream.ai")
public class DreamAiProperties {

    /**
     * 默认模型 key。当请求未指定 modelKey 且没有任何模型标记 primary 时兜底。
     */
    private String defaultModel;

    /**
     * 供应商（接入点）列表。
     */
    private List<ProviderProperties> providers = new ArrayList<>();
}