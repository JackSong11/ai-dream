package com.example.dream.service.core.ai.agent.session;

import com.example.dream.service.core.ai.agent.message.AgentMessage;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 会话，对应 nanobot session.manager.Session。
 * <p>
 * 持有一段对话的完整历史消息、元数据（含运行时检查点、pending 标记等）与时间戳。
 * 提供历史裁剪、消息追加、检查点存取等能力，供状态机与 Runner 使用。
 *
 * @author dream
 */
@Data
public class AgentSession {

    /**
     * 会话唯一 key（channel:chatId 或统一会话 key）。
     */
    private final String key;

    /**
     * 历史消息列表。
     */
    private final List<AgentMessage> messages = new ArrayList<>();

    /**
     * 会话元数据：runtime_checkpoint、pending_user_turn、goal_state 等。
     */
    private final Map<String, Object> metadata = new HashMap<>();

    /**
     * 会话摘要（记忆压缩后生成）。
     */
    private String summary;

    /**
     * 创建时间。
     */
    private Instant createdAt = Instant.now();

    /**
     * 最后更新时间。
     */
    private Instant updatedAt = Instant.now();

    public AgentSession(String key) {
        this.key = key;
    }

    /**
     * 追加一条消息并刷新更新时间。
     *
     * @param role    角色
     * @param content 内容
     * @return 追加的消息对象
     */
    public AgentMessage addMessage(String role, String content) {
        AgentMessage msg = AgentMessage.of(role, content);
        messages.add(msg);
        updatedAt = Instant.now();
        return msg;
    }

    /**
     * 追加一条完整消息。
     *
     * @param message 消息对象
     */
    public void addMessage(AgentMessage message) {
        messages.add(message);
        updatedAt = Instant.now();
    }

    /**
     * 追加一条命令类消息（不进入 LLM 上下文），对应 nanobot add_message(_command=True)。
     */
    public AgentMessage addCommandMessage(String role, String content) {
        AgentMessage msg = AgentMessage.builder()
                .role(role).content(content).command(true).build();
        messages.add(msg);
        updatedAt = Instant.now();
        return msg;
    }

    /**
     * 追加一条子代理结果消息，对应 nanobot _persist_subagent_followup。
     */
    public AgentMessage addSubagentResult(String content, String senderId, String taskId) {
        AgentMessage msg = AgentMessage.builder()
                .role("assistant").content(content).senderId(senderId)
                .injectedEvent("subagent_result").subagentTaskId(taskId).build();
        messages.add(msg);
        updatedAt = Instant.now();
        return msg;
    }

    /**
     * 获取用于 LLM 上下文的历史消息（按数量上限裁剪，保留最近的消息）。
     * <p>
     * 对应 nanobot Session.get_history 的 max_messages 语义：先过滤掉命令类
     * 消息（command=true，不进入 LLM 上下文），再按数量上限保留最近的消息。
     *
     * @param maxMessages 最大消息数，&lt;=0 表示不限
     * @return 裁剪后的历史消息（新列表）
     */
    public List<AgentMessage> getHistory(int maxMessages) {
        List<AgentMessage> visible = new ArrayList<>();
        for (AgentMessage m : messages) {
            if (m != null && !m.isCommand()) {
                visible.add(m);
            }
        }
        if (maxMessages <= 0 || visible.size() <= maxMessages) {
            return visible;
        }
        return new ArrayList<>(visible.subList(visible.size() - maxMessages, visible.size()));
    }

    // ==================== runtime checkpoint（中断回合恢复） ====================

    /**
     * 写入运行时检查点，对应 nanobot _set_runtime_checkpoint。
     * <p>
     * 在工具执行过程中周期性持久化「已完成的工具结果 + 未完成的工具调用 +
     * 当前 assistant 消息」，以便回合被中断后可物化到历史。
     *
     * @param checkpoint 检查点载荷
     */
    public void setRuntimeCheckpoint(RuntimeCheckpoint checkpoint) {
        metadata.put(SessionKeys.RUNTIME_CHECKPOINT, checkpoint);
    }

    /**
     * 读取运行时检查点，不存在时返回 null。
     */
    public RuntimeCheckpoint getRuntimeCheckpoint() {
        Object v = metadata.get(SessionKeys.RUNTIME_CHECKPOINT);
        return v instanceof RuntimeCheckpoint rc ? rc : null;
    }

    /**
     * 清除运行时检查点，对应 nanobot _clear_runtime_checkpoint。
     */
    public void clearRuntimeCheckpoint() {
        metadata.remove(SessionKeys.RUNTIME_CHECKPOINT);
    }

    /**
     * 将未完成回合的检查点物化到会话历史，对应 nanobot _restore_runtime_checkpoint。
     * <p>
     * 追加 assistant 消息与已完成的 tool 结果；未完成的 tool 调用补一条
     * "中断" 占位结果。通过尾部重叠检测避免重复追加。清理完成后返回 true。
     *
     * @return 是否发生了恢复
     */
    public boolean restoreRuntimeCheckpoint() {
        RuntimeCheckpoint checkpoint = getRuntimeCheckpoint();
        if (checkpoint == null) {
            return false;
        }
        List<AgentMessage> restored = new ArrayList<>();
        if (checkpoint.getAssistantMessage() != null) {
            restored.add(checkpoint.getAssistantMessage());
        }
        if (checkpoint.getCompletedToolResults() != null) {
            restored.addAll(checkpoint.getCompletedToolResults());
        }
        if (checkpoint.getPendingToolCalls() != null) {
            for (AgentMessage.ToolCall tc : checkpoint.getPendingToolCalls()) {
                restored.add(AgentMessage.builder()
                        .role("tool")
                        .toolCallId(tc.getId())
                        .name(tc.getName() == null ? "tool" : tc.getName())
                        .content("错误：工具执行前任务被中断。")
                        .build());
            }
        }

        // 尾部重叠检测：避免与已有历史重复
        int overlap = 0;
        int maxOverlap = Math.min(messages.size(), restored.size());
        for (int size = maxOverlap; size > 0; size--) {
            boolean match = true;
            for (int i = 0; i < size; i++) {
                AgentMessage left = messages.get(messages.size() - size + i);
                AgentMessage right = restored.get(i);
                if (!sameMessage(left, right)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                overlap = size;
                break;
            }
        }
        for (int i = overlap; i < restored.size(); i++) {
            messages.add(restored.get(i));
        }
        metadata.remove(SessionKeys.PENDING_USER_TURN);
        clearRuntimeCheckpoint();
        updatedAt = Instant.now();
        return true;
    }

    /**
     * 关闭仅持久化了用户消息就崩溃的回合，对应 nanobot _restore_pending_user_turn。
     *
     * @return 是否发生了补全
     */
    public boolean restorePendingUserTurn() {
        if (!metaFlag(SessionKeys.PENDING_USER_TURN)) {
            return false;
        }
        AgentMessage last = lastMessage();
        if (last != null && "user".equals(last.getRole())) {
            addMessage("assistant", "错误：上一轮任务在生成响应前被中断。");
        }
        metadata.remove(SessionKeys.PENDING_USER_TURN);
        return true;
    }

    /**
     * 判断子代理结果是否已存在（按 subagentTaskId 去重），
     * 对应 nanobot _persist_subagent_followup 的去重逻辑。
     */
    public boolean hasSubagentResult(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        return messages.stream().anyMatch(m ->
                "subagent_result".equals(m.getInjectedEvent())
                        && taskId.equals(m.getSubagentTaskId()));
    }

    private boolean sameMessage(AgentMessage a, AgentMessage b) {
        if (a == null || b == null) {
            return a == b;
        }
        return java.util.Objects.equals(a.getRole(), b.getRole())
                && java.util.Objects.equals(a.getContent(), b.getContent())
                && java.util.Objects.equals(a.getToolCallId(), b.getToolCallId())
                && java.util.Objects.equals(a.getName(), b.getName());
    }

    /**
     * 读取元数据布尔标记。
     */
    public boolean metaFlag(String key) {
        return Boolean.TRUE.equals(metadata.get(key));
    }

    /**
     * 最后一条消息，空时返回 null。
     */
    public AgentMessage lastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    /**
     * 清空会话历史（对应 /new 命令）。
     */
    public void clear() {
        messages.clear();
        summary = null;
        metadata.remove(SessionKeys.RUNTIME_CHECKPOINT);
        metadata.remove(SessionKeys.PENDING_USER_TURN);
        updatedAt = Instant.now();
    }

    /**
     * 会话元数据键常量。
     */
    public static final class SessionKeys {
        public static final String RUNTIME_CHECKPOINT = "runtime_checkpoint";
        public static final String PENDING_USER_TURN = "pending_user_turn";

        private SessionKeys() {
        }
    }
}