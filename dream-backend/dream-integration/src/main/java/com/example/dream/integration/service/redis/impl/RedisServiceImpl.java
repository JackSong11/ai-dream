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
            // Stream 不存在时先创建（XADD 一条占位再删，或用 MKSTREAM）。这里用 createGroup 的 MKSTREAM 语义。
            if (Boolean.FALSE.equals(redisTemplate.hasKey(streamKey))) {
                // 通过创建组并指定 $ 起点，Spring 的 createGroup 默认会 MKSTREAM
                redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), groupName);
                return;
            }
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), groupName);
        } catch (Exception e) {
            // 组已存在（BUSYGROUP）视为正常
            log.debug("streamCreateGroupIfAbsent: 组可能已存在, streamKey={}, group={}, msg={}",
                    streamKey, groupName, e.getMessage());
        }
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