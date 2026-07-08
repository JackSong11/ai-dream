package com.example.dream.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2.x ObjectMapper 配置。
 *
 * <p>Spring Boot 4.1 的 JacksonAutoConfiguration 已升级到 Jackson 3.x
 * （包名 {@code tools.jackson.databind.ObjectMapper}），容器中默认注册的是
 * Jackson 3.x 的 ObjectMapper。而项目现有代码大量依赖 Jackson 2.x 的
 * {@code com.fasterxml.jackson.databind.ObjectMapper}（如 DocTaskConsumer、
 * DocumentBizServiceImpl 使用的 JsonNode / ObjectNode 等）。</p>
 *
 * <p>这里显式声明一个 Jackson 2.x 的 ObjectMapper bean，供上述代码注入使用，
 * 与容器内 Jackson 3.x 的 bean 并存，避免 "No qualifying bean" 启动失败。</p>
 *
 * @author dream
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return objectMapper;
    }
}