package com.example.dream.processor.doc.config;

import com.example.dream.common.constant.DocTaskConstants;
import com.example.dream.integration.service.redis.RedisService;
import com.example.dream.processor.doc.DocTaskConsumer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 文档解析任务 Stream 监听容器。
 *
 * <p>基于 Redis Stream + 消费者组实现异步任务消费（对应 RagFlow task_executor 的常驻消费循环）：
 * <ul>
 *   <li>启动时确保消费者组存在（幂等）</li>
 *   <li>以消费者组模式订阅队列，手动 ack（autoAck=false）</li>
 *   <li>消息交由 {@link DocTaskConsumer} 处理</li>
 * </ul>
 * </p>
 *
 * @author dream
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocTaskStreamConfig {

    private final RedisConnectionFactory redisConnectionFactory;

    private final RedisService redisService;

    private final DocTaskConsumer docTaskConsumer;

    /**
     * 消费者名称：进程内唯一，便于 Redis 侧区分（对应 RagFlow CONSUMER_NAME）。
     */
    private final String consumerName = "dream_consumer_" + UUID.randomUUID().toString().substring(0, 8);

    private StreamMessageListenerContainer<String, @NonNull MapRecord<String, String, String>> container;

    private Subscription subscription;

    @PostConstruct
    public void start() {
        // 1) 确保消费者组存在（幂等，组已存在则静默）
        redisService.streamCreateGroupIfAbsent(DocTaskConstants.SVR_QUEUE, DocTaskConstants.SVR_CONSUMER_GROUP);

        // 2) 构建监听容器：轮询超时 2s，每次最多取 1 条，手动 ack
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, @NonNull MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .batchSize(1)
                        .build();
        this.container = StreamMessageListenerContainer.create(redisConnectionFactory, options);

        // 3) 消费者组模式订阅，autoAck=false（由消费者处理完成后手动 ack）
        this.subscription = container.receive(
                Consumer.from(DocTaskConstants.SVR_CONSUMER_GROUP, consumerName),
                StreamOffset.create(DocTaskConstants.SVR_QUEUE, ReadOffset.lastConsumed()),
                docTaskConsumer);

        container.start();
        log.info("文档解析任务消费者已启动, queue={}, group={}, consumer={}",
                DocTaskConstants.SVR_QUEUE, DocTaskConstants.SVR_CONSUMER_GROUP, consumerName);
    }

    @PreDestroy
    public void stop() {
        try {
            if (subscription != null) {
                subscription.cancel();
            }
            if (container != null) {
                container.stop();
            }
            log.info("文档解析任务消费者已停止, consumer={}", consumerName);
        } catch (Exception e) {
            log.warn("停止文档解析任务消费者异常, consumer={}", consumerName, e);
        }
    }
}