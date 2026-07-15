package com.example.dream.service.core.ai.agent;

import com.example.dream.service.core.ai.agent.hook.AgentCallbacks;
import com.example.dream.service.core.ai.agent.hook.AgentHook;
import com.example.dream.service.core.ai.agent.message.AgentMessage;
import com.example.dream.service.core.ai.agent.message.InboundMessage;
import com.example.dream.service.core.ai.agent.message.OutboundMessage;
import com.example.dream.service.core.ai.agent.session.AgentSession;
import com.example.dream.service.core.ai.agent.state.StateTraceEntry;
import com.example.dream.service.core.ai.agent.state.TurnState;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 回合上下文，对应 nanobot loop.py 的 TurnContext dataclass。
 * <p>
 * 在状态机各状态处理器之间传递的可变载体，聚合本回合从输入消息到
 * 最终输出的全部中间状态。
 *
 * @author dream
 */
@Data
public class TurnContext {

    /**
     * 入站消息。
     */
    private InboundMessage msg;

    /**
     * 会话 key。
     */
    private final String sessionKey;

    /**
     * 当前状态。
     */
    private TurnState state = TurnState.RESTORE;

    /**
     * 回合唯一 id。
     */
    private final String turnId;

    /**
     * 模型 key（本回合使用），为空则用默认模型。
     */
    private String modelKey;

    /**
     * 系统提示词。
     */
    private String systemPrompt;

    /**
     * 原始用户文本。
     */
    private String originalUserText;

    /**
     * 会话对象。
     */
    private AgentSession session;

    /**
     * 历史消息。
     */
    private List<AgentMessage> history = new ArrayList<>();

    /**
     * 初始消息列表（送入 LLM）。
     */
    private List<AgentMessage> initialMessages = new ArrayList<>();

    /**
     * 会话摘要（compact 阶段产出）。
     */
    private String pendingSummary;

    /**
     * 最终响应内容。
     */
    private String finalContent;

    /**
     * 使用过的工具。
     */
    private List<String> toolsUsed = new ArrayList<>();

    /**
     * 本回合产生的全部消息。
     */
    private List<AgentMessage> allMessages = new ArrayList<>();

    /**
     * 停止原因。
     */
    private String stopReason = "";

    /**
     * 出站消息。
     */
    private OutboundMessage outbound;

    /**
     * 是否抑制响应。
     */
    private boolean suppressResponse = false;

    /**
     * 工具对象数组。
     */
    private Object[] tools = new Object[0];

    /**
     * 回调集合。
     */
    private AgentCallbacks callbacks = AgentCallbacks.empty();

    /**
     * 回合钩子。
     */
    private List<AgentHook> hooks = new ArrayList<>();

    /**
     * 是否流式。
     */
    private boolean stream = false;

    /**
     * 回合起始时间（毫秒）。
     */
    private final long turnStartedAtMs = System.currentTimeMillis();

    /**
     * 回合延迟（毫秒）。
     */
    private Integer turnLatencyMs;

    /**
     * 状态执行轨迹。
     */
    private final List<StateTraceEntry> trace = new ArrayList<>();

    public TurnContext(InboundMessage msg, String sessionKey, String turnId) {
        this.msg = msg;
        this.sessionKey = sessionKey;
        this.turnId = turnId;
    }
}