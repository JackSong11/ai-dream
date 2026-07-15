package com.example.dream.service.core.ai.agent.session;

import com.example.dream.service.core.ai.agent.message.AgentMessage;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时检查点，对应 nanobot loop.py 中 runtime_checkpoint 载荷。
 * <p>
 * 在工具执行过程中周期性写入，记录本轮 LLM 已产出的 assistant 消息、
 * 已完成的工具结果与尚未完成的工具调用。回合被中断（如 /stop 或崩溃）后，
 * 借助本检查点将部分上下文物化进会话历史，避免用户丢失中间结果。
 *
 * @author dream
 */
@Data
@Builder
public class RuntimeCheckpoint {

    /**
     * 本轮 LLM 产出的 assistant 消息（可能含 tool_calls）。
     */
    private AgentMessage assistantMessage;

    /**
     * 已完成的工具结果消息列表。
     */
    @Builder.Default
    private List<AgentMessage> completedToolResults = new ArrayList<>();

    /**
     * 尚未完成的工具调用列表（中断时补占位结果）。
     */
    @Builder.Default
    private List<AgentMessage.ToolCall> pendingToolCalls = new ArrayList<>();
}