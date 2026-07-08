package com.example.dream.processor.doc;

import com.example.dream.common.constant.DocTaskConstants;
import com.example.dream.common.dto.DocTaskMessage;
import com.example.dream.integration.service.redis.RedisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

/**
 * 文档解析任务消费者。
 *
 * <p>监听 Redis Stream 队列 {@link DocTaskConstants#SVR_QUEUE}，取出任务消息后
 * 委托 {@link DocumentTaskHandler} 执行分块 → 向量化 → 写 ES 全流程。
 * 对应 RagFlow task_executor.handle_task + collect 的消费侧职责。</p>
 *
 * @author dream
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocTaskConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final ObjectMapper objectMapper;

    private final RedisService redisService;

    private final DocumentTaskHandler documentTaskHandler;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        String recordId = record.getId().getValue();
        String payload = record.getValue().get(DocTaskConstants.MSG_FIELD_PAYLOAD);
        if (payload == null || payload.isEmpty()) {
            log.warn("收到空消息, recordId={}, 直接 ack", recordId);
            ack(recordId);
            return;
        }

        DocTaskMessage message;
        try {
            message = objectMapper.readValue(payload, DocTaskMessage.class);
        } catch (Exception e) {
            log.error("消息反序列化失败, recordId={}, payload={}", recordId, payload, e);
            // 无法解析的消息 ack 掉，避免无限重投
            ack(recordId);
            return;
        }

        try {
            log.info("开始处理文档解析任务, taskId={}, docId={}", message.getTaskId(), message.getDocId());
            documentTaskHandler.handle(message);
            log.info("文档解析任务完成, taskId={}, docId={}", message.getTaskId(), message.getDocId());
        } catch (Exception e) {
            // 对应 RagFlow set_progress(prog=-1)：处理失败时由 handler 内部标记进度为失败
            log.error("文档解析任务处理失败, taskId={}, docId={}", message.getTaskId(), message.getDocId(), e);
        } finally {
            // 对应 RagFlow redis_msg.ack()：无论成败都确认，失败靠进度状态体现，不做自动重投
            ack(recordId);
        }
    }

    private void ack(String recordId) {
        redisService.streamAck(DocTaskConstants.SVR_QUEUE, DocTaskConstants.SVR_CONSUMER_GROUP, recordId);
    }
}