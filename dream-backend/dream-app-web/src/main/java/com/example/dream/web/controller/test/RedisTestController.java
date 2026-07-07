package com.example.dream.web.controller.test;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/redis-test")
@RequiredArgsConstructor
public class RedisTestController {

    // 1. 注入默认自动装配的 RedisTemplate (Object, Object)
    private final RedisTemplate<String, Object> redisTemplate;

    // 2. 注入默认自动装配的 StringRedisTemplate (用于对比)
    private final StringRedisTemplate stringRedisTemplate;

    @GetMapping("/bug")
    public Map<String, Object> testBug() {
        // 使用默认的 redisTemplate 存入一个简单的 key-value
        String key = "user:100";
        String value = "Jack";

        redisTemplate.opsForValue().set(key, value);

        // 尝试从内存中再读出来（代码层面上看起来一切正常）
        Object cachedValue = redisTemplate.opsForValue().get(key);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("msg", "请立刻去 Redis 终端（redis-cli）查看真实的 key 长什么样！");
        result.put("result", "Code read value: " + cachedValue);
        return result;
    }

    @GetMapping("/success")
    public Map<String, Object> testSuccess() {
        String key = "user:200";
        String value = "Rose";
        // 使用 StringRedisTemplate 存入数据
        stringRedisTemplate.opsForValue().set(key, value);

        // 从内存中读取
        Object cachedValue = stringRedisTemplate.opsForValue().get(key);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("msg", "请立刻去 Redis 终端（redis-cli）查看真实的 key 长什么样！");
        result.put("result", "Code read value: " + cachedValue);
        return result;
    }
}