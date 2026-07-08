package com.example.dream.integration.service.redis.impl;

import com.example.dream.integration.service.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis 服务实现。
 * <p>基于 {@link RedisTemplate} 实现通用 Redis 操作。</p>
 *
 * @author dream
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RedisServiceImpl implements RedisService {

    private final RedisTemplate<String, Object> redisTemplate;

    // ==================== String 操作 ====================

    @Override
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    @Override
    public void set(String key, Object value, Duration timeout) {
        // 2. 将 long + TimeUnit 转换为 Duration
        // 重构前（传统方式）：很容易数错 0 的个数，或者传错 TimeUnit 导致时间不对（比如把秒传成毫秒）
        // redisService.set("sms:code:123", code, 5, TimeUnit.MINUTES);

        // 重构后（现代 Java 风格）：语义极其清晰，看一眼就知道是 2 小时或 5 分钟，完全消除了单位传错的风险
        // redisService.set("user:token:123", tokenData, Duration.ofHours(2));
        // redisService.set("sms:code:123", code, Duration.ofMinutes(5));

        // 也可以支持天、秒、毫秒等
        // redisService.set("temp:lock", lock, Duration.ofSeconds(30));
        redisTemplate.opsForValue().set(key, value, timeout);
    }

    @Override
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public Boolean delete(String key) {
        return redisTemplate.delete(key);
    }

    @Override
    public Long delete(Collection<String> keys) {
        return redisTemplate.delete(keys);
    }

    @Override
    public Boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }

    @Override
    public Boolean expire(String key, Duration timeout) {
        // 3. 顺便把 expire 方法也用同样的方式修复，防止后续报错
        return redisTemplate.expire(key, timeout);
    }

    @Override
    public Long getExpire(String key, TimeUnit timeUnit) {
        return redisTemplate.getExpire(key, timeUnit);
    }

    // ==================== Hash 操作 ====================

    @Override
    public void hPut(String key, String hashKey, Object value) {
        redisTemplate.opsForHash().put(key, hashKey, value);
    }

    @Override
    public void hPutAll(String key, Map<String, Object> map) {
        redisTemplate.opsForHash().putAll(key, map);
    }

    @Override
    public Object hGet(String key, String hashKey) {
        return redisTemplate.opsForHash().get(key, hashKey);
    }

    @Override
    public Map<Object, Object> hGetAll(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    @Override
    public Long hDelete(String key, Object... hashKeys) {
        return redisTemplate.opsForHash().delete(key, hashKeys);
    }

    @Override
    public Boolean hHasKey(String key, String hashKey) {
        return redisTemplate.opsForHash().hasKey(key, hashKey);
    }

    // ==================== 自增/自减 ====================

    @Override
    public Long increment(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }

    @Override
    public Long decrement(String key, long delta) {
        return redisTemplate.opsForValue().decrement(key, delta);
    }

    // ==================== Stream 消息队列操作 ====================

    @Override
    public String streamAdd(String streamKey, Map<String, String> message) {
        try {
            MapRecord<String, String, String> record = StreamRecords.mapBacked(message).withStreamKey(streamKey);
            RecordId recordId = redisTemplate.opsForStream().add(record);
            return recordId == null ? null : recordId.getValue();
        } catch (Exception e) {
            log.error("streamAdd 失败, streamKey={}", streamKey, e);
            return null;
        }
    }

    @Override
    public void streamCreateGroupIfAbsent(String streamKey, String groupName) {
        try {
            // 直接创建。如果 Stream 不存在，底层 MKSTREAM 会自动创建 Stream
            // 如果要消费历史数据用 ReadOffset.from("0-0")；如果只消费新数据用 ReadOffset.latest()
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), groupName);
        } catch (Exception e) {
            // 捕获 BUSYGROUP 异常，如果是其他异常（如 Redis 连接断开）建议根据实际业务考虑是否向上抛出
            // 注意：Spring 会将底层的 RedisBusyException 包装成 RedisSystemException，
            // 其顶层 message 为 "Error in execution"，不含 BUSYGROUP，因此必须遍历整个异常链判断
            if (containsBusyGroup(e)) {
                log.debug("消费者组已存在，无需重复创建: streamKey={}, group={}", streamKey, groupName);
            } else {
                log.error("创建消费者组失败: streamKey={}, group={}", streamKey, groupName, e);
                throw e; // 如果是其他网络或语法错误，建议抛出
            }
        }
    }

    /**
     * 遍历整个异常链，判断是否为 Redis 消费组已存在（BUSYGROUP）异常
     */
    private boolean containsBusyGroup(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    public Long streamAck(String streamKey, String groupName, String recordId) {
        try {
            return redisTemplate.opsForStream().acknowledge(streamKey, groupName, recordId);
        } catch (Exception e) {
            log.error("streamAck 失败, streamKey={}, group={}, recordId={}", streamKey, groupName, recordId, e);
            return 0L;
        }
    }
}