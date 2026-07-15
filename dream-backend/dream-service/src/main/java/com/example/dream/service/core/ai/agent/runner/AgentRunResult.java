package com.example.dream.service.core.ai.agent.runner;

import com.example.dream.service.core.ai.agent.message.AgentMessage;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 运行结果，对应 nanobot runner 返回的
 * (final_content, tools_used, messages, stop_reason, had_injections)。
 *
 * @author dream
 */
@Data
@Builder
public class AgentRunResult {

    /**
     * 最终响应内容。
     */
    private String finalContent;

    /**
     * 本回合调用过的工具名称列表。
     */
    @Builder.Default
    private List<String> toolsUsed = new ArrayList<>();

    /**
     * 本回合产生的全部消息（含 assistant / tool 消息），用于回写会话历史。
     */
    @Builder.Default
    private List<AgentMessage> messages = new ArrayList<>();

    /**
     * 停止原因：stop / max_iterations / error / empty_final_response 等。
     */
    @Builder.Default
    private String stopReason = "stop";

    /**
     * 本回合是否发生了中途消息注入。
     */
    @Builder.Default
    private boolean hadInjections = false;

    /**
     * token 使用量（可选）。
     */
    private Integer totalTokens;
}