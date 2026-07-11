package com.example.dream.web.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
public class SseStreamController {

    // 1. 使用线程安全的 Map 管理客户端连接
    private static final Map<String, SseEmitter> EMITTER_MAP = new ConcurrentHashMap<>();

    // 2. 异步执行任务的线程池（在现代 Java 21+ 环境下，强烈建议使用虚拟线程）
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    @GetMapping(value = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handleSse(String clientId) {
        // 3. 创建 SseEmitter 实例，设置超时时间（单位毫秒，这里设置 5 分钟）
        // 默认不设或设为 0 代表永不超时，但不推荐，容易导致连接泄漏
        SseEmitter emitter = new SseEmitter(300_000L); 

        // 4. 注册生命周期回调，保证资源释放
        emitter.onCompletion(() -> EMITTER_MAP.remove(clientId));
        emitter.onTimeout(() -> {
            EMITTER_MAP.remove(clientId);
            emitter.complete(); // 显式结束
        });
        emitter.onError((ex) -> EMITTER_MAP.remove(clientId));

        // 保存连接
        EMITTER_MAP.put(clientId, emitter);

        // 5. 异步向客户端推送数据
        executorService.submit(() -> {
            try {
                // 先发送一个连接成功的探测包（解决部分浏览器连接初期可能丢失事件的问题）
                emitter.send(SseEmitter.event().name("connect").data("Connected!"));

                // 模拟大模型流式输出或业务流式推流
                for (int i = 1; i <= 10; i++) {
                    Thread.sleep(500); // 模拟耗时过程

                    // 标准写法：通过事件构造器发送
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(i))          // 事件ID，用于客户端断线重连（Last-Event-ID）
                            .name("message")                // 事件名称
                            .data("Data chunk " + i)        // 实际数据
                            .reconnectTime(3000));          // 断线后客户端自动重连的间隔（毫秒）
                }

                // 6. 正常传输结束
                emitter.complete();
            } catch (IOException e) {
                // 客户端提前断开连接时，send 会抛出 IOException (如 ClientAbortException)
                emitter.completeWithError(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.completeWithError(e);
            }
        });

        // 7. 立即返回 emitter 实例给 Spring MVC
        return emitter;
    }
}