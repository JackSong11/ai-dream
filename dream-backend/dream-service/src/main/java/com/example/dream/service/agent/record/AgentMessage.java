package com.example.dream.service.agent.record;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A provider-neutral, persistable message used by the agent runtime.
 */
// 告诉 JSON 解析库（Jackson），在把这个对象转成 JSON 字符串时，自动忽略所有值为 null 的字段。
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentMessage(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String toolName,
        Boolean hidden) {

    public static AgentMessage user(String content) {
        return new AgentMessage("user", content, null, null, null, null);
    }

    public static AgentMessage assistant(String content) {
        return new AgentMessage("assistant", content, null, null, null, null);
    }

    public static AgentMessage assistantToolCalls(String content, List<ToolCall> calls) {
        return new AgentMessage("assistant", content, calls, null, null, true);
    }

    public static AgentMessage tool(String id, String name, String result) {
        return new AgentMessage("tool", result, null, id, name, true);
    }

    public record ToolCall(String id, String type, String name, String arguments) {
    }
}
