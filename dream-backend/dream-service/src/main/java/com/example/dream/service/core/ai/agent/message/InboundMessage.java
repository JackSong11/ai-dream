package com.example.dream.service.core.ai.agent.message;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 入站消息，对应 nanobot bus.events.InboundMessage。
 * <p>
 * 承载来自渠道（cli/web/system 等）的一条用户消息，包括内容、发送者、
 * 会话标识、多媒体附件与元数据。
 *
 * @author dream
 */
@Data
@Builder(toBuilder = true)
public class InboundMessage {

    /**
     * 来源渠道，如 cli / web / system。
     */
    @Builder.Default
    private String channel = "cli";

    /**
     * 发送者标识，如 user / subagent。
     */
    @Builder.Default
    private String senderId = "user";

    /**
     * 会话对话标识（渠道内）。
     */
    @Builder.Default
    private String chatId = "direct";

    /**
     * 消息文本内容。
     */
    @Builder.Default
    private String content = "";

    /**
     * 多媒体附件路径列表。
     */
    @Builder.Default
    private List<String> media = new ArrayList<>();

    /**
     * 元数据（message_id、context_chat_id、流式开关等）。
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 会话 key 覆盖值（用于统一会话或跨会话路由）。
     */
    private String sessionKeyOverride;

    /**
     * 计算有效会话 key：优先覆盖值，否则 channel:chatId。
     *
     * @return 会话 key
     */
    public String getSessionKey() {
        if (sessionKeyOverride != null && !sessionKeyOverride.isBlank()) {
            return sessionKeyOverride;
        }
        return channel + ":" + chatId;
    }

    /**
     * 安全读取元数据值。
     *
     * @param key 元数据键
     * @return 值，不存在时返回 null
     */
    public Object metaValue(String key) {
        return metadata == null ? null : metadata.get(key);
    }
}