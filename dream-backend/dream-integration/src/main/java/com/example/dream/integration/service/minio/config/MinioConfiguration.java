package com.example.dream.integration.service.minio.config;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.dream.integration.service.minio.OssService;

/**
 * MinIO 自动配置。
 * <p>当配置项 {@code dream.minio.enabled=true}（默认）时生效，
 * 自动构建 {@link MinioClient} 与 {@link OssService}。</p>
 * <p>采用显式 {@code @Bean} 声明而非组件扫描，使本模块自包含、即插即用，
 * 不依赖使用方的包扫描路径。</p>
 *
 * @author dream
 */
@Configuration
@Slf4j
public class MinioConfiguration {

    // 使用冒号 : 后面跟的是默认值
    @Value("${minio.endpoint:http://localhost:9000}")
    private String endpoint;

    @Value("${minio.access-key:minioadmin}")
    private String accessKey;

    @Value("${minio.secret-key:minioadmin}")
    private String secretKey;

    /**
     * 构建 MinioClient。
     *
     * @return MinioClient 实例
     */
    @Bean
    public MinioClient minioClient() {
        log.info("初始化 MinioClient, endpoint={}", endpoint);
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

}