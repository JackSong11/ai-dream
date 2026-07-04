package com.example.dream.integration.service.redis;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis 通用操作接口。
 * <p>对底层 Redis 操作进行封装，供上层业务调用。</p>
 *
 * @author dream
 */
public interface RedisService {

    // ==================== String 操作 ====================

    /**
     * 设置键值对。
     *
     * @param key   键
     * @param value 值
     */
    void set(String key, Object value);

    /**
     * 设置键值对并指定过期时间。
     *
     * @param key     键
     * @param value   值
     * @param timeout 使用 Duration 代替传统的 long 和 TimeUnit
     */
    void set(String key, Object value, Duration timeout);

    /**
     * 获取值。
     *
     * @param key 键
     * @return 值
     */
    Object get(String key);

    /**
     * 删除键。
     *
     * @param key 键
     * @return 是否删除成功
     */
    Boolean delete(String key);

    /**
     * 批量删除键。
     *
     * @param keys 键集合
     * @return 删除的数量
     */
    Long delete(Collection<String> keys);

    /**
     * 判断键是否存在。
     *
     * @param key 键
     * @return 是否存在
     */
    Boolean hasKey(String key);

    /**
     * 设置过期时间。
     *
     * @param key      键
     * @param timeout 使用 Duration 代替传统的 long 和 TimeUnit
     * @return 是否设置成功
     */
    Boolean expire(String key, Duration timeout);

    /**
     * 获取过期时间。
     *
     * @param key      键
     * @param timeUnit 时间单位
     * @return 过期时间（-1 表示永不过期，-2 表示键不存在）
     */
    Long getExpire(String key, TimeUnit timeUnit);

    // ==================== Hash 操作 ====================

    /**
     * 设置 Hash 字段值。
     *
     * @param key     键
     * @param hashKey Hash 字段名
     * @param value   值
     */
    void hPut(String key, String hashKey, Object value);

    /**
     * 批量设置 Hash 字段值。
     *
     * @param key 键
     * @param map 字段名与值的映射
     */
    void hPutAll(String key, Map<String, Object> map);

    /**
     * 获取 Hash 字段值。
     *
     * @param key     键
     * @param hashKey Hash 字段名
     * @return 值
     */
    Object hGet(String key, String hashKey);

    /**
     * 获取 Hash 所有字段值。
     *
     * @param key 键
     * @return 字段名与值的映射
     */
    Map<Object, Object> hGetAll(String key);

    /**
     * 删除 Hash 字段。
     *
     * @param key      键
     * @param hashKeys Hash 字段名
     * @return 删除的数量
     */
    Long hDelete(String key, Object... hashKeys);

    /**
     * 判断 Hash 字段是否存在。
     *
     * @param key     键
     * @param hashKey Hash 字段名
     * @return 是否存在
     */
    Boolean hHasKey(String key, String hashKey);

    // ==================== 自增/自减 ====================

    /**
     * 递增。
     *
     * @param key   键
     * @param delta 增量（必须大于 0）
     * @return 递增后的值
     */
    Long increment(String key, long delta);

    /**
     * 递减。
     *
     * @param key   键
     * @param delta 减量（必须大于 0）
     * @return 递减后的值
     */
    Long decrement(String key, long delta);
}