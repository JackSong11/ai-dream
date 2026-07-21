package com.example.dream.service.agent.record;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dream.agent")
public class AgentRuntimeProperties {
    private int maxIterations = 8;
    private int maxHistoryMessages = 80;
    private int streamChunkSize = 12;
    private int maxAttempts = 3;
    private long retryBackoffMillis = 250;
    private long timeoutSeconds = 120;
    private int maxInjectionsPerTurn = 20;
    private String systemPrompt = "你是 DreamAI，一个可靠的智能助手。需要工具时先调用工具；基于工具结果回答，不要伪造结果。";
}
