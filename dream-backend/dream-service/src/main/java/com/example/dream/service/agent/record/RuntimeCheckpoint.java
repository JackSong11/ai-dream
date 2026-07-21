package com.example.dream.service.agent.record;

import java.util.List;

/**
 * Latest durable agent execution boundary; it restores context, not a Java stack.
 */
public record RuntimeCheckpoint(
        Phase phase, // 当前执行阶段：指示 Agent 目前停留在生命周期的哪个步骤（见下方枚举解析）。
        int iteration, // 循环轮次/迭代次数：Agent 通常在一个 while 循环中不断 "思考-调用-再思考"。该字段防止死循环（比如限制最多迭代 10 次）。
        String modelKey, // 模型标识：指定当前 Checkpoint 使用的大模型配置（如 "gpt-4o" 或 "claude-3-5-sonnet"），支持中途切换或保持模型一致性。
        AgentMessage assistantMessage, // AI 发起调用的原始消息：即上一轮 AI 返回的带 toolCalls 的消息。恢复现场时需要用到它。
        List<AgentMessage> completedToolResults, // 已完成的工具结果：如果 AI 批处理调用了 3 个工具，目前已经执行完了 2 个，这里就暂存这 2 个结果。
        List<AgentMessage.ToolCall> pendingToolCalls) { // 待执行的工具调用：还没有执行或正在等待执行的工具列表。

    public enum Phase {
        AWAITING_TOOLS, // 等待工具执行
        TOOLS_COMPLETED, // 工具执行完毕
        FINAL_RESPONSE // 最终响应
    }
}
