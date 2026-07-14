package com.example.dream.service.core.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 多模型能力配置装配入口。
 * 启用 {@link DreamAiProperties} 的配置绑定。
 *
 * @author dream
 */
@Configuration
@EnableConfigurationProperties(DreamAiProperties.class)
public class DreamAiConfiguration {
}