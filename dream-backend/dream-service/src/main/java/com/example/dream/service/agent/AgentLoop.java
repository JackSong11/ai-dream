package com.example.dream.service.agent;

import com.example.dream.common.exception.BizException;
import com.example.dream.dal.po.ChatConversationPO;
import com.example.dream.dal.po.ChatDialogPO;
import com.example.dream.integration.service.redis.lock.DistributedLockService;
import com.example.dream.service.agent.record.*;
import com.example.dream.service.core.DialogCoreService;
import com.example.dream.service.core.ai.registry.ChatClientRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Durable Spring AI 2.0 agent turn state machine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLoop {
    private final ChatClientRegistry chatClientRegistry;
    private final DialogCoreService dialogs;
    private final AgentCheckpointStore store;
    private final AgentToolRegistry tools;
    private final AgentRuntimeProperties properties;
    private final DistributedLockService distributedLockService;
    private final AgentToolExecutionRuntime toolRuntime;
    private final ObservationRegistry observationRegistry;
    private final Map<Long, Queue<String>> pendingMessages = new ConcurrentHashMap<>();
    private final Set<Long> activeConversations = ConcurrentHashMap.newKeySet();

    /**
     * A follow-up arriving during a turn is consumed before that turn finishes.
     * 线程安全且带防御性校验的“消息插队/追加”功能。
     * 它的核心作用是：在系统正在处理某次对话（Turn）时，如果用户又发送了新的追问（Follow-up），将该追问存入对应对话的待处理队列中，以便在当前 Turn 结束前一并消费处理。
     */
    public void inject(Long conversationId, String text) {
        if (conversationId == null || !StringUtils.hasText(text)) return;
        pendingMessages.computeIfAbsent(conversationId, ignored -> new ConcurrentLinkedQueue<>()).add(text.trim());
    }

    public AgentRunResult run(AgentRunRequest request, Consumer<String> onDelta) {
        Observation observation = Observation.createNotStarted("dream.agent.loop", observationRegistry)
                .contextualName("agent loop")
                .lowCardinalityKeyValue("dream.agent.stream", Boolean.toString(onDelta != null))
                .highCardinalityKeyValue("dream.agent.dialog.id", String.valueOf(request.dialogId()))
                .highCardinalityKeyValue("dream.agent.conversation.id", String.valueOf(request.conversationId()));
        if (StringUtils.hasText(request.modelKey())) {
            observation.highCardinalityKeyValue("dream.agent.request.model", request.modelKey());
        }
        return observation.observe(() -> runObserved(request, onDelta));
    }

    private AgentRunResult runObserved(AgentRunRequest request, Consumer<String> onDelta) {
        if (activeConversations.contains(request.conversationId())) {
            inject(request.conversationId(), request.userText());
            return new AgentRunResult("", List.of(), "injected", 0);
        }
        String lockKey = "dream:lock:agent:conversation:" + request.conversationId();
        try (DistributedLockService.LockHandle ignored = distributedLockService.acquire(lockKey)) {
            // 【双重检查 (DCL)】：防止在获取锁的过程中，其他线程已经抢先开始处理了
            if (!activeConversations.add(request.conversationId())) {
                inject(request.conversationId(), request.userText());
                return new AgentRunResult("", List.of(), "injected", 0);
            }
            try {
                // 正式执行 Agent 推理逻辑
                return execute(request, onDelta);
            } finally {
                activeConversations.remove(request.conversationId());
            }
        }
    }

    private AgentRunResult execute(AgentRunRequest request, Consumer<String> onDelta) {
        Turn turn = restoreTurn(request);
        transition(turn, TurnState.COMPACT);
        compact(turn.history);
        transition(turn, TurnState.BUILD);
        turn.history.add(AgentMessage.user(request.userText()));
        store.save(turn.conversation, new AgentSessionState(turn.history, null, true));
        transition(turn, TurnState.RUN);

        try {
            do {
                runModel(turn, onDelta);
                drainInjections(turn);
            } while (!turn.injected.isEmpty() && turn.iterations < properties.getMaxIterations());
            transition(turn, TurnState.SAVE);
            AgentMessage finalMessage = AgentMessage.assistant(turn.answer);
            store.save(turn.conversation, new AgentSessionState(turn.history,
                    new RuntimeCheckpoint(RuntimeCheckpoint.Phase.FINAL_RESPONSE, turn.iterations,
                            turn.modelKey, finalMessage, List.of(), List.of()), true));
            turn.history.add(finalMessage);
            store.save(turn.conversation, new AgentSessionState(turn.history, null, false));
            transition(turn, TurnState.RESPOND);
            transition(turn, TurnState.DONE);
            Observation current = observationRegistry.getCurrentObservation();
            if (current != null) {
                current.lowCardinalityKeyValue("dream.agent.stop.reason", turn.stopReason)
                        .highCardinalityKeyValue("dream.agent.iterations", Integer.toString(turn.iterations))
                        .highCardinalityKeyValue("dream.agent.tools.used", String.join(",", turn.toolsUsed));
            }
            return new AgentRunResult(turn.answer, List.copyOf(turn.toolsUsed), turn.stopReason, turn.iterations);
        } catch (AgentToolExecutionRuntime.MaxIterationsExceededException e) {
            turn.stopReason = "max_iterations";
            store.save(turn.conversation, new AgentSessionState(turn.history, null, false));
            throw new BizException(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Agent turn interrupted: conversation={}, state={}", request.conversationId(), turn.state, e);
            throw e;
        } finally {
            pendingMessages.remove(request.conversationId());
        }
    }

    /**
     * 这段代码的主要作用是恢复（或加载）一个 Agent 对话轮次（Turn）的上下文信息
     * restore - 恢复
     * 1. 权限与存在性校验：安全第一，先确认用户是否有权限访问该对话。
     * 2. 状态恢复：把持久化存储（如 Redis/MySQL）里的历史消息和 Session 状态加载出来。
     * 3. 模型兜底策略：设计了一套清晰的模型选择优先级（请求参数 > 基础对话配置 > 系统默认值）。
     * 4. 组装返回：最终构建一个包含全部上下文信息的 Turn 领域对象。
     */
    private Turn restoreTurn(AgentRunRequest request) {
        ChatDialogPO dialog = dialogs.getOwnedValidDialog(request.dialogId(), request.userId());
        if (dialog == null) throw new BizException("对话不存在或无权访问");
        ChatConversationPO conversation = store.requireOwned(request.conversationId(), request.dialogId(), request.userId());
        AgentSessionState loaded = restore(store.load(conversation), conversation);
        String modelKey = StringUtils.hasText(request.modelKey()) ? request.modelKey()
                : StringUtils.hasText(dialog.getLlmId()) ? dialog.getLlmId() : chatClientRegistry.getDefaultModelKey();
        Observation current = observationRegistry.getCurrentObservation();
        if (current != null) current.highCardinalityKeyValue("dream.agent.model", modelKey);
        return new Turn(conversation, loaded.messages(), modelKey);
    }

    private void runModel(Turn turn, Consumer<String> onDelta) {
        ChatClient client = chatClientRegistry.getClient(turn.modelKey);
        AgentToolExecutionRuntime.BoundaryListener listener = checkpointListener(turn);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try (AgentToolExecutionRuntime.Scope ignored = toolRuntime.open(properties.getMaxIterations(), listener)) {
                Prompt prompt = buildPrompt(turn.history, turn.toolsUsed);
                long started = System.nanoTime();
                ChatResponse response;
                if (onDelta == null) {
                    response = client.prompt(prompt).call().chatResponse();
                } else {
                    StringBuilder answer = new StringBuilder();
                    final ChatResponse[] latest = new ChatResponse[1];
                    client.prompt(prompt).stream().chatResponse()
                            .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                            .doOnNext(chunk -> {
                                latest[0] = chunk;
                                String delta = chunk.getResult() == null ? null : chunk.getResult().getOutput().getText();
                                if (StringUtils.hasLength(delta)) {
                                    answer.append(delta);
                                    onDelta.accept(delta);
                                }
                            }).blockLast();
                    response = latest[0];
                    turn.answer = answer.toString();
                }
                if (Duration.ofNanos(System.nanoTime() - started).toSeconds() > properties.getTimeoutSeconds())
                    throw new BizException("Agent model call timed out");
                if (response == null || response.getResult() == null) throw new BizException("模型未返回响应");
                if (onDelta == null) turn.answer = Objects.toString(response.getResult().getOutput().getText(), "");
                if (!StringUtils.hasText(turn.answer)) turn.answer = "模型返回了空响应";
                turn.iterations = Math.max(1, turn.toolIterations + 1);
                turn.stopReason = "final_response";
                return;
            } catch (AgentToolExecutionRuntime.MaxIterationsExceededException e) {
                throw e;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < properties.getMaxAttempts()) {
                    try {
                        Thread.sleep(properties.getRetryBackoffMillis() * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw Objects.requireNonNull(last);
    }

    private AgentToolExecutionRuntime.BoundaryListener checkpointListener(Turn turn) {
        return new AgentToolExecutionRuntime.BoundaryListener() {
            @Override
            public void awaiting(int iteration, AssistantMessage assistant) {
                turn.toolIterations = Math.max(turn.toolIterations, iteration);
                AgentMessage message = fromAssistant(assistant);
                store.save(turn.conversation, new AgentSessionState(turn.history,
                        new RuntimeCheckpoint(RuntimeCheckpoint.Phase.AWAITING_TOOLS, iteration, turn.modelKey,
                                message, List.of(), message.toolCalls()), true));
            }

            @Override
            public void completed(int iteration, AssistantMessage assistant, ToolResponseMessage response,
                                  List<Message> ignored) {
                AgentMessage assistantMessage = fromAssistant(assistant);
                List<AgentMessage> results = fromToolResponse(response);
                appendMissingSuffix(turn.history, concat(assistantMessage, results));
                store.save(turn.conversation, new AgentSessionState(turn.history,
                        new RuntimeCheckpoint(RuntimeCheckpoint.Phase.TOOLS_COMPLETED, iteration, turn.modelKey,
                                assistantMessage, results, List.of()), true));
            }
        };
    }

    private void drainInjections(Turn turn) {
        turn.injected.clear();
        Queue<String> queue = pendingMessages.get(turn.conversation.getId());
        if (queue == null) return;
        String text;
        while ((text = queue.poll()) != null && turn.injected.size() < properties.getMaxInjectionsPerTurn()) {
            turn.injected.add(text);
            turn.history.add(AgentMessage.user(text));
        }
        if (!turn.injected.isEmpty()) store.save(turn.conversation, new AgentSessionState(turn.history, null, true));
    }

    private Prompt buildPrompt(List<AgentMessage> history, List<String> toolsUsed) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(properties.getSystemPrompt()));
        int start = safeHistoryStart(history);
        Set<String> calls = new HashSet<>();
        for (AgentMessage item : history.subList(start, history.size())) {
            switch (item.role()) {
                case "user" -> messages.add(new UserMessage(item.content()));
                case "system" -> messages.add(new SystemMessage(item.content()));
                case "assistant" -> {
                    List<AssistantMessage.ToolCall> toolCalls = item.toolCalls() == null ? List.of() : item.toolCalls().stream()
                            .map(c -> {
                                calls.add(c.id());
                                return new AssistantMessage.ToolCall(c.id(), c.type(), c.name(), c.arguments());
                            }).toList();
                    messages.add(AssistantMessage.builder().content(Objects.toString(item.content(), "")).toolCalls(toolCalls).build());
                }
                case "tool" -> {
                    if (calls.contains(item.toolCallId())) messages.add(ToolResponseMessage.builder().responses(List.of(
                            new ToolResponseMessage.ToolResponse(item.toolCallId(), item.toolName(), item.content()))).build());
                }
                default -> {
                }
            }
        }
        return new Prompt(messages, ToolCallingChatOptions.builder().toolCallbacks(tools.trackingCallbacks(toolsUsed::add)).build());
    }

    private int safeHistoryStart(List<AgentMessage> history) {
        int start = Math.max(0, history.size() - properties.getMaxHistoryMessages());
        while (start < history.size() && "tool".equals(history.get(start).role())) start++;
        return start;
    }

    private void compact(List<AgentMessage> history) {
        int max = properties.getMaxHistoryMessages() * 2;
        if (history.size() <= max) return;
        int remove = history.size() - properties.getMaxHistoryMessages();
        while (remove < history.size() && "tool".equals(history.get(remove).role())) remove++;
        history.subList(0, remove).clear();
    }

    private AgentSessionState restore(AgentSessionState state, ChatConversationPO conversation) {
        List<AgentMessage> history = state.messages();
        RuntimeCheckpoint checkpoint = state.checkpoint();
        if (checkpoint != null) {
            List<AgentMessage> recovered = new ArrayList<>();
            if (checkpoint.assistantMessage() != null) recovered.add(checkpoint.assistantMessage());
            if (checkpoint.phase() == RuntimeCheckpoint.Phase.AWAITING_TOOLS) {
                for (AgentMessage.ToolCall call : checkpoint.pendingToolCalls())
                    recovered.add(AgentMessage.tool(call.id(), call.name(),
                            "Error: task interrupted before this tool finished."));
            } else if (checkpoint.phase() == RuntimeCheckpoint.Phase.TOOLS_COMPLETED)
                recovered.addAll(checkpoint.completedToolResults());
            appendMissingSuffix(history, recovered);
        } else if (state.pendingUserTurn() && !CollectionUtils.isEmpty(history) && "user".equals(history.getLast().role())) {
            history.add(AgentMessage.assistant("Error: task interrupted before a response was generated."));
        }
        if (checkpoint != null || state.pendingUserTurn()) {
            AgentSessionState restored = new AgentSessionState(history, null, false);
            store.save(conversation, restored);
            return restored;
        }
        return state;
    }

    private void appendMissingSuffix(List<AgentMessage> history, List<AgentMessage> recovered) {
        int overlap = 0;
        for (int size = Math.min(history.size(), recovered.size()); size > 0; size--) {
            if (history.subList(history.size() - size, history.size()).equals(recovered.subList(0, size))) {
                overlap = size;
                break;
            }
        }
        history.addAll(recovered.subList(overlap, recovered.size()));
    }

    private AgentMessage fromAssistant(AssistantMessage message) {
        return AgentMessage.assistantToolCalls(message.getText(), message.getToolCalls().stream()
                .map(c -> new AgentMessage.ToolCall(c.id(), c.type(), c.name(), c.arguments())).toList());
    }

    private List<AgentMessage> fromToolResponse(ToolResponseMessage response) {
        if (response == null) return List.of();
        return response.getResponses().stream().map(r -> AgentMessage.tool(r.id(), r.name(), r.responseData())).toList();
    }

    private List<AgentMessage> concat(AgentMessage first, List<AgentMessage> rest) {
        List<AgentMessage> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private void transition(Turn turn, TurnState next) {
        log.debug("Agent state {} -> {}", turn.state, next);
        turn.state = next;
    }

    private enum TurnState {RESTORE, COMPACT, COMMAND, BUILD, RUN, SAVE, RESPOND, DONE}

    private static final class Turn {
        final ChatConversationPO conversation;
        final List<AgentMessage> history;
        final String modelKey;
        final List<String> toolsUsed = new ArrayList<>();
        final List<String> injected = new ArrayList<>();
        TurnState state = TurnState.RESTORE;
        String answer = "";
        String stopReason = "";
        int toolIterations;
        int iterations;

        Turn(ChatConversationPO conversation, List<AgentMessage> history, String modelKey) {
            this.conversation = conversation;
            this.history = history;
            this.modelKey = modelKey;
        }
    }
}
