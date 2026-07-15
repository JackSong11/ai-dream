package com.example.dream.service.core.ai.agent;

import com.example.dream.service.core.ai.agent.context.Consolidator;
import com.example.dream.service.core.ai.agent.context.ContextBuilder;
import com.example.dream.service.core.ai.agent.hook.AgentCallbacks;
import com.example.dream.service.core.ai.agent.hook.AgentHook;
import com.example.dream.service.core.ai.agent.message.AgentMessage;
import com.example.dream.service.core.ai.agent.message.InboundMessage;
import com.example.dream.service.core.ai.agent.message.OutboundMessage;
import com.example.dream.service.core.ai.agent.runner.AgentRunResult;
import com.example.dream.service.core.ai.agent.runner.AgentRunSpec;
import com.example.dream.service.core.ai.agent.runner.AgentRunner;
import com.example.dream.service.core.ai.agent.session.AgentSession;
import com.example.dream.service.core.ai.agent.session.AgentSessionManager;
import com.example.dream.service.core.ai.agent.state.StateTraceEntry;
import com.example.dream.service.core.ai.agent.state.TurnState;
import com.example.dream.service.core.ai.agent.subagent.SubagentManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Agent 核心处理引擎，对应 nanobot agent.loop.AgentLoop。
 * <p>
 * <b>用 Spring AI 2.0 内建能力承载 nanobot 手写机制，功能语义 1:1 还原：</b>
 * <ul>
 *   <li>事件驱动状态机：RESTORE→COMPACT→COMMAND→BUILD→RUN→SAVE→RESPOND→DONE；</li>
 *   <li>多轮工具调用循环：委托 {@link AgentRunner}（内建 ChatClient/ToolCallingManager）；</li>
 *   <li>模型切换：复用 {@link com.example.dream.service.core.ai.registry.ChatModelRegistry}；</li>
 *   <li>会话历史：{@link AgentSessionManager}；记忆压缩：{@link Consolidator}；</li>
 *   <li>流式输出：{@link AgentCallbacks} + AgentRunner 的 Flux 流。</li>
 * </ul>
 * <p>
 * 提供 {@link #processDirect} 直连入口，供 Web/CLI 层同步或流式调用。
 * 每个会话串行执行（ReentrantLock），跨会话并发。
 *
 * @author dream
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private final AgentSessionManager sessions;
    private final AgentRunner runner;
    private final ContextBuilder context;
    private final Consolidator consolidator;

    /**
     * 子智能体管理器：用于在主 Agent 工具集中挂载 spawn 工具。
     */
    private final SubagentManager subagentManager;

    /**
     * 每会话串行锁。
     */
    private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    /**
     * 事件驱动状态转移表，对应 nanobot 的 _TRANSITIONS。
     * key = "state|event"，value = 下一状态。
     */
    private static final Map<String, TurnState> TRANSITIONS = new HashMap<>();

    static {
        TRANSITIONS.put(key(TurnState.RESTORE, "ok"), TurnState.COMPACT);
        TRANSITIONS.put(key(TurnState.COMPACT, "ok"), TurnState.COMMAND);
        TRANSITIONS.put(key(TurnState.COMMAND, "dispatch"), TurnState.BUILD);
        TRANSITIONS.put(key(TurnState.COMMAND, "shortcut"), TurnState.DONE);
        TRANSITIONS.put(key(TurnState.BUILD, "ok"), TurnState.RUN);
        TRANSITIONS.put(key(TurnState.RUN, "ok"), TurnState.SAVE);
        TRANSITIONS.put(key(TurnState.SAVE, "ok"), TurnState.RESPOND);
        TRANSITIONS.put(key(TurnState.RESPOND, "ok"), TurnState.DONE);
    }

    private static String key(TurnState state, String event) {
        return state.name() + "|" + event;
    }

    // ==================== 对外入口 ====================

    /**
     * 直连处理一条消息并返回响应（同步）。
     *
     * @param content    用户输入
     * @param sessionKey 会话 key
     * @param modelKey   模型 key（为空用默认模型）
     * @param tools      工具对象数组
     * @return 出站消息，可能为 null（被抑制时）
     */
    public OutboundMessage processDirect(String content, String sessionKey,
                                         String modelKey, Object... tools) {
        InboundMessage msg = InboundMessage.builder()
                .channel("api")
                .chatId(sessionKey)
                .content(content)
                .sessionKeyOverride(sessionKey)
                .build();
        return process(msg, modelKey, false, AgentCallbacks.empty(), List.of(), tools);
    }

    /**
     * 直连处理一条消息（流式），delta 通过 callbacks.onStream 回调输出。
     *
     * @param content    用户输入
     * @param sessionKey 会话 key
     * @param modelKey   模型 key
     * @param callbacks  流式回调
     * @param tools      工具对象数组
     * @return 出站消息（含完整内容）
     */
    public OutboundMessage processStream(String content, String sessionKey, String modelKey,
                                         AgentCallbacks callbacks, Object... tools) {
        InboundMessage msg = InboundMessage.builder()
                .channel("api")
                .chatId(sessionKey)
                .content(content)
                .sessionKeyOverride(sessionKey)
                .build();
        return process(msg, modelKey, true, callbacks, List.of(), tools);
    }

    /**
     * 处理一条入站消息的核心方法：加锁、驱动状态机、返回结果。
     */
    public OutboundMessage process(InboundMessage msg, String modelKey, boolean stream,
                                   AgentCallbacks callbacks, List<AgentHook> hooks, Object... tools) {
        String sessionKey = msg.getSessionKey();
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionKey, k -> new ReentrantLock());
        lock.lock();
        try {
            TurnContext ctx = new TurnContext(msg, sessionKey,
                    sessionKey + ":" + System.nanoTime());
            ctx.setModelKey(modelKey);
            ctx.setStream(stream);
            ctx.setCallbacks(callbacks == null ? AgentCallbacks.empty() : callbacks);
            ctx.setHooks(hooks == null ? List.of() : hooks);
            // 组装工具集：业务工具 + spawn 子智能体工具（子 Agent 用同一批业务工具，
            // 但不含 spawn 以防无限递归）。MCP 工具由 AgentRunner 自动合并。
            Object[] baseTools = tools == null ? new Object[0] : tools;
            Object[] withSpawn = new Object[baseTools.length + 1];
            System.arraycopy(baseTools, 0, withSpawn, 0, baseTools.length);
            withSpawn[baseTools.length] = new com.example.dream.service.core.ai.agent.subagent.SpawnTool(
                    subagentManager, modelKey, baseTools);
            ctx.setTools(withSpawn);
            ctx.setOriginalUserText(msg.getContent());
            return drive(ctx);
        } finally {
            lock.unlock();
        }    }

    // ==================== 状态机驱动 ====================

    /**
     * 驱动状态机运行直至 DONE，对应 nanobot _process_message 的主循环。
     */
    private OutboundMessage drive(TurnContext ctx) {
        while (ctx.getState() != TurnState.DONE) {
            long t0 = System.nanoTime();
            String event;
            try {
                event = handle(ctx);
            } catch (Exception ex) {
                double dur = (System.nanoTime() - t0) / 1_000_000.0;
                ctx.getTrace().add(new StateTraceEntry(ctx.getState(), t0, dur, "", "exception"));
                log.error("[AgentLoop] 状态 {} 执行异常, turnId={}", ctx.getState(), ctx.getTurnId(), ex);
                throw new RuntimeException("Agent 回合执行失败: " + ex.getMessage(), ex);
            }
            double dur = (System.nanoTime() - t0) / 1_000_000.0;
            ctx.getTrace().add(new StateTraceEntry(ctx.getState(), t0, dur, event));
            log.debug("[turn {}] 状态 {} 耗时 {}ms -> 事件 {}",
                    ctx.getTurnId(), ctx.getState(), String.format("%.1f", dur), event);

            TurnState next = TRANSITIONS.get(key(ctx.getState(), event));
            if (next == null) {
                throw new IllegalStateException(
                        "[turn " + ctx.getTurnId() + "] 无状态转移: " + ctx.getState() + " on " + event);
            }
            ctx.setState(next);
        }
        log.debug("[turn {}] 回合完成, 共 {} 个状态", ctx.getTurnId(), ctx.getTrace().size());
        return ctx.getOutbound();
    }

    /**
     * 根据当前状态分派到对应处理器。
     */
    private String handle(TurnContext ctx) {
        return switch (ctx.getState()) {
            case RESTORE -> stateRestore(ctx);
            case COMPACT -> stateCompact(ctx);
            case COMMAND -> stateCommand(ctx);
            case BUILD -> stateBuild(ctx);
            case RUN -> stateRun(ctx);
            case SAVE -> stateSave(ctx);
            case RESPOND -> stateRespond(ctx);
            default -> throw new IllegalStateException("无处理器: " + ctx.getState());
        };
    }

    // ==================== 状态处理器 ====================

    /**
     * RESTORE：恢复会话与未完成回合检查点，对应 _state_restore。
     */
    private String stateRestore(TurnContext ctx) {
        InboundMessage msg = ctx.getMsg();
        String preview = msg.getContent().length() > 80
                ? msg.getContent().substring(0, 80) + "..." : msg.getContent();
        log.info("[AgentLoop] 处理消息 {}:{}: {}", msg.getChannel(), msg.getSenderId(), preview);

        AgentSession session = sessions.getOrCreate(ctx.getSessionKey());
        ctx.setSession(session);

        // 1. 恢复运行时检查点：把上一回合中断时的 assistant/tool 结果物化进历史
        //    （对应 nanobot _restore_runtime_checkpoint）
        if (session.getRuntimeCheckpoint() != null) {
            session.restoreRuntimeCheckpoint();
            sessions.save(session);
            log.info("[AgentLoop] 会话 {} 已恢复中断回合的部分上下文", ctx.getSessionKey());
        }
        // 2. 恢复仅持久化了用户消息就中断的回合（对应 nanobot _restore_pending_user_turn）
        if (session.restorePendingUserTurn()) {
            sessions.save(session);
        }
        return "ok";
    }

    /**
     * COMPACT：按需压缩会话记忆，对应 _state_compact。
     */
    private String stateCompact(TurnContext ctx) {
        boolean compacted = consolidator.maybeConsolidate(ctx.getSession());
        if (compacted) {
            ctx.setPendingSummary(ctx.getSession().getSummary());
            log.info("[AgentLoop] 会话 {} 触发记忆压缩", ctx.getSessionKey());
        } else {
            ctx.setPendingSummary(ctx.getSession().getSummary());
        }
        return "ok";
    }

    /**
     * COMMAND：处理内置命令，对应 _state_command。
     * <p>
     * 支持 /new（清空会话）等快捷命令，命中则跳过 BUILD/RUN 直接结束。
     */
    private String stateCommand(TurnContext ctx) {
        String raw = ctx.getMsg().getContent() == null ? "" : ctx.getMsg().getContent().trim();
        if ("/new".equalsIgnoreCase(raw)) {
            ctx.getSession().clear();
            sessions.save(ctx.getSession());
            ctx.setOutbound(OutboundMessage.of(
                    ctx.getMsg().getChannel(), ctx.getMsg().getChatId(), "已开启新会话。"));
            return "shortcut";
        }
        if ("/help".equalsIgnoreCase(raw)) {
            ctx.setOutbound(OutboundMessage.of(ctx.getMsg().getChannel(), ctx.getMsg().getChatId(),
                    "可用命令：/new 开启新会话，/help 查看帮助。"));
            return "shortcut";
        }
        return "dispatch";
    }

    /**
     * BUILD：构建初始消息列表并预持久化用户消息，对应 _state_build。
     */
    private String stateBuild(TurnContext ctx) {
        AgentSession session = ctx.getSession();
        // 组装历史（保留最近 40 条）
        ctx.setHistory(session.getHistory(40));

        List<AgentMessage> initial = context.buildMessages(
                ctx.getSystemPrompt(),
                ctx.getPendingSummary(),
                ctx.getHistory(),
                ctx.getMsg().getContent(),
                null);
        ctx.setInitialMessages(initial);

        // 预持久化用户消息，并标记 pending（回合中断可恢复）
        if (StringUtils.hasText(ctx.getMsg().getContent())) {
            session.addMessage("user", ctx.getMsg().getContent());
            session.getMetadata().put(AgentSession.SessionKeys.PENDING_USER_TURN, true);
            sessions.save(session);
        }
        return "ok";
    }

    /**
     * RUN：运行 Agent 迭代循环（多轮工具调用），对应 _state_run。
     * <p>
     * 委托 {@link AgentRunner}，由 Spring AI ChatClient 内建的工具调用循环
     * 自动完成「模型请求工具 → 框架执行工具 → 回填结果 → 再次请求模型」，
     * 直到产出最终回复。流式与同步由 ctx.stream 决定。
     */
    private String stateRun(TurnContext ctx) {
        ctx.getCallbacks().progress("running");

        AgentRunSpec spec = AgentRunSpec.builder()
                .initialMessages(ctx.getInitialMessages())
                .modelKey(ctx.getModelKey())
                .tools(ctx.getTools())
                .stream(ctx.isStream())
                .callbacks(ctx.getCallbacks())
                .hooks(ctx.getHooks())
                .sessionKey(ctx.getSessionKey())
                .build();

        AgentRunResult result = runner.run(spec);
        ctx.setFinalContent(result.getFinalContent());
        ctx.setToolsUsed(result.getToolsUsed());
        ctx.setAllMessages(result.getMessages());
        ctx.setStopReason(result.getStopReason());
        return "ok";
    }

    /**
     * SAVE：保存本回合消息到会话历史，对应 _state_save。
     */
    private String stateSave(TurnContext ctx) {
        AgentSession session = ctx.getSession();

        // 空响应兜底（对应 EMPTY_FINAL_RESPONSE_MESSAGE）
        if ((ctx.getFinalContent() == null || ctx.getFinalContent().isBlank())
                && !ctx.isSuppressResponse()) {
            ctx.setFinalContent("（本轮未生成有效响应）");
        }

        // 回写本回合产生的 assistant / tool 消息（过滤孤儿 tool 结果，
        // 对应 nanobot _save_turn 的 declared_tool_call_ids 校验）
        java.util.Set<String> declaredIds = declaredToolCallIds(session);
        if (ctx.getAllMessages() != null) {
            for (AgentMessage m : ctx.getAllMessages()) {
                if (m == null) {
                    continue;
                }
                // 跳过空 assistant 消息，避免污染上下文
                if ("assistant".equals(m.getRole())
                        && (m.getContent() == null || m.getContent().isBlank())
                        && (m.getToolCalls() == null || m.getToolCalls().isEmpty())) {
                    continue;
                }
                // 丢弃未声明的孤儿 tool 结果，避免污染后续 provider 请求
                if ("tool".equals(m.getRole())) {
                    if (m.getToolCallId() == null || !declaredIds.contains(m.getToolCallId())) {
                        log.warn("[AgentLoop] 丢弃孤儿 tool 结果 {} (会话 {})",
                                m.getToolCallId(), ctx.getSessionKey());
                        continue;
                    }
                }
                session.addMessage(m);
                // 新增 assistant 的 tool_calls 也纳入已声明集合
                if ("assistant".equals(m.getRole()) && m.getToolCalls() != null) {
                    m.getToolCalls().forEach(tc -> {
                        if (tc.getId() != null) {
                            declaredIds.add(tc.getId());
                        }
                    });
                }
            }
        }
        // 记录延迟并落到最后一条 assistant 消息
        ctx.setTurnLatencyMs((int) Math.max(0, System.currentTimeMillis() - ctx.getTurnStartedAtMs()));
        AgentMessage last = session.lastMessage();
        if (last != null && "assistant".equals(last.getRole())) {
            last.setLatencyMs(ctx.getTurnLatencyMs());
        }

        // 回写本回合使用过的工具名（对应 nanobot tools_used）
        if (ctx.getToolsUsed() != null && !ctx.getToolsUsed().isEmpty()) {
            session.getMetadata().put("last_tools_used", new java.util.ArrayList<>(ctx.getToolsUsed()));
        }

        // 清理 pending 标记与运行时检查点，然后持久化
        session.getMetadata().remove(AgentSession.SessionKeys.PENDING_USER_TURN);
        session.clearRuntimeCheckpoint();
        sessions.save(session);
        return "ok";
    }

    /**
     * 收集会话中已声明的工具调用 id（用于孤儿 tool 结果过滤），
     * 对应 nanobot _save_turn 的 declared_tool_call_ids。
     */
    private java.util.Set<String> declaredToolCallIds(AgentSession session) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (AgentMessage m : session.getMessages()) {
            if ("assistant".equals(m.getRole()) && m.getToolCalls() != null) {
                for (AgentMessage.ToolCall tc : m.getToolCalls()) {
                    if (tc.getId() != null) {
                        ids.add(tc.getId());
                    }
                }
            }
        }
        return ids;
    }

    /**
     * RESPOND：组装出站消息，对应 _state_respond + _assemble_outbound。
     */
    private String stateRespond(TurnContext ctx) {
        if (ctx.isSuppressResponse()) {
            ctx.setOutbound(null);
            return "ok";
        }
        Map<String, Object> meta = new HashMap<>();
        if (ctx.getTurnLatencyMs() != null) {
            meta.put("latency_ms", ctx.getTurnLatencyMs());
        }
        meta.put("stop_reason", ctx.getStopReason());
        String event = ctx.getCallbacks().hasStream()
                && !"error".equals(ctx.getStopReason()) ? "streamed_response" : null;

        OutboundMessage outbound = OutboundMessage.builder()
                .channel(ctx.getMsg().getChannel())
                .chatId(ctx.getMsg().getChatId())
                .content(ctx.getFinalContent())
                .event(event)
                .metadata(meta)
                .build();
        ctx.setOutbound(outbound);
        return "ok";
    }
}