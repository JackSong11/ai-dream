package com.example.dream.service.core.ai.agent.message;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 出站消息，对应 nanobot bus.events.OutboundMessage。
 * <p>
 * Agent 一个回合处理完成后返回给渠道的响应载荷。
 *
 * @author dream
 */
@Data
@Builder(toBuilder = true)
public class OutboundMessage {

    /**
     * 目标渠道。
     */
    private String channel;

    /**
     * 目标会话对话标识。
     */
    private String chatId;

    /**
     * 响应文本内容。
     */
    @Builder.Default
    private String content = "";

    /**
     * 事件类型标记（如 streamed_response），可为空。
     */
    private String event;

    /**
     * 元数据（latency_ms、stop_reason 等）。
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    public static OutboundMessage of(String channel, String chatId, String content) {
        return OutboundMessage.builder()
                .channel(channel)
                .chatId(chatId)
                .content(content)
                .build();
    }
}