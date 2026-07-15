package com.example.dream.service.core.ai.agent.message;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 会话历史中的一条消息，对应 nanobot loop.py 中以 dict 表示的
 * {role, content, tool_calls, tool_call_id, ...} 消息结构。
 * <p>
 * 与 Spring AI 的 {@code org.springframework.ai.chat.messages.Message} 相互转换，
 * 本类作为会话持久化与回合内传递的中间载体。
 *
 * @author dream
 */
@Data
@Builder(toBuilder = true)
public class AgentMessage {

    /**
     * 角色：system / user / assistant / tool。
     */
    private String role;

    /**
     * 文本内容。
     */
    private String content;

    /**
     * assistant 消息发起的工具调用列表（每项含 id/name/arguments）。
     */
    @Builder.Default
    private List<ToolCall> toolCalls = new ArrayList<>();

    /**
     * tool 消息对应的工具调用 id。
     */
    private String toolCallId;

    /**
     * tool 消息对应的工具名称。
     */
    private String name;

    /**
     * 发送者标识（如 subagent）。
     */
    private String senderId;

    /**
     * 多媒体附件路径列表（图片/文档等）。
     */
    @Builder.Default
    private List<String> media = new ArrayList<>();

    /**
     * 注入事件类型（如 subagent_result），对应 nanobot injected_event。
     */
    private String injectedEvent;

    /**
     * 关联的子代理任务 id，对应 nanobot subagent_task_id。
     */
    private String subagentTaskId;

    /**
     * 是否为命令类消息（对应 nanobot _command 标记），
     * 用于在构建 LLM 上下文时过滤。
     */
    @Builder.Default
    private boolean command = false;

    /**
     * 时间戳。
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * 本回合延迟（毫秒），仅最后一条 assistant 消息记录。
     */
    private Integer latencyMs;

    public static AgentMessage of(String role, String content) {
        return AgentMessage.builder().role(role).content(content).build();
    }

    /**
     * 是否为空 assistant 消息（无内容且无工具调用），此类消息会污染上下文。
     */
    public boolean isEmptyAssistant() {
        return "assistant".equals(role)
                && (content == null || content.isBlank())
                && (toolCalls == null || toolCalls.isEmpty());
    }

    /**
     * 工具调用条目。
     */
    @Data
    @Builder
    public static class ToolCall {
        private String id;
        private String name;
        private String arguments;
    }
}