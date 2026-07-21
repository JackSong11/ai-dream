package com.example.dream.service.agent;

import com.example.dream.common.exception.BizException;
import com.example.dream.dal.po.ChatConversationPO;
import com.example.dream.dal.po.ChatDialogPO;
import com.example.dream.integration.service.redis.lock.DistributedLockService;
import com.example.dream.service.agent.record.*;
import com.example.dream.service.core.DialogCoreService;
import com.example.dream.service.core.ai.registry.ChatClientRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Java/Spring AI port of nanobot's core turn loop.
 * Checkpoints restore provider-valid conversation context, not a suspended JVM stack.
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

    public AgentRunResult run(AgentRunRequest request, Consumer<String> onDelta) {
        String lockKey = "dream:lock:agent:conversation:" + request.conversationId();
        try (DistributedLockService.LockHandle ignored = distributedLockService.acquire(lockKey)) {
            return runLocked(request, onDelta == null ? delta -> {
            } : onDelta);
        }
    }

    private AgentRunResult runLocked(AgentRunRequest request, Consumer<String> onDelta) {
        ChatDialogPO dialog = dialogs.getOwnedValidDialog(request.dialogId(), request.userId());
        if (dialog == null) throw new BizException("对话不存在或无权访问");
        ChatConversationPO conversation = store.requireOwned(request.conversationId(), request.dialogId(), request.userId());
        AgentSessionState loaded = restore(store.load(conversation), conversation);
        List<AgentMessage> history = loaded.messages();

        // 构建：在向任何提供程序发出请求之前，持久化用户输入。
        history.add(AgentMessage.user(request.userText()));
        store.save(conversation, new AgentSessionState(history, null, true));

        String modelKey = StringUtils.hasText(request.modelKey()) ? request.modelKey()
                : StringUtils.hasText(dialog.getLlmId()) ? dialog.getLlmId() : chatClientRegistry.getDefaultModelKey();
        ChatClient client = chatClientRegistry.getClient(modelKey);
        List<String> toolsUsed = new ArrayList<>();

        try {
            ChatResponse response = client.prompt(buildPrompt(history, toolsUsed))
                    .call()
                    .chatResponse();
            if (response == null || response.getResult() == null) {
                throw new BizException("模型未返回响应");
            }
            AssistantMessage assistant = response.getResult().getOutput();
            String answer = StringUtils.hasText(assistant.getText()) ? assistant.getText() : "模型返回了空响应";
            AgentMessage finalMessage = AgentMessage.assistant(answer);
            store.save(conversation, new AgentSessionState(history,
                    new RuntimeCheckpoint(RuntimeCheckpoint.Phase.FINAL_RESPONSE, 1,
                            modelKey, finalMessage, List.of(), List.of()), true));
            history.add(finalMessage);
            emitChunks(answer, onDelta);
            store.save(conversation, new AgentSessionState(history, null, false));
            return new AgentRunResult(answer, toolsUsed, "final_response", 1);
        } catch (RuntimeException e) {
            log.warn("Agent turn interrupted: conversation={}", request.conversationId(), e);
            throw e;
        }
    }

    private AgentSessionState restore(AgentSessionState state, ChatConversationPO conversation) {
        List<AgentMessage> history = state.messages();
        RuntimeCheckpoint checkpoint = state.checkpoint();
        // 场景一：处理包含 Checkpoint 的中断恢复
        if (checkpoint != null) {
            List<AgentMessage> recovered = new ArrayList<>();
            // 1.1 恢复中断前大模型生成的最后一条 Assistant 消息（通常包含 tool_calls）
            if (checkpoint.assistantMessage() != null) recovered.add(checkpoint.assistantMessage());

            // 1.2 情况 A：中断发生时，工具还在执行中（AWAITING_TOOLS）
            if (checkpoint.phase() == RuntimeCheckpoint.Phase.AWAITING_TOOLS) {
                for (AgentMessage.ToolCall call : checkpoint.pendingToolCalls()) {
                    recovered.add(AgentMessage.tool(call.id(), call.name(),
                            "Error: task interrupted before this tool finished."));
                }
            }
            // 1.3 情况 B：中断发生时，所有工具都已经执行完了（TOOLS_COMPLETED）
            else if (checkpoint.phase() == RuntimeCheckpoint.Phase.TOOLS_COMPLETED) {
                recovered.addAll(checkpoint.completedToolResults());
            }

            // 1.4 将修复好的消息片段（recovered）安全地追加到主历史记录（history）末尾
            appendMissingSuffix(history, recovered);
        }
        // 场景二：处理没有 Checkpoint，但在等待回答时中断
        else if (state.pendingUserTurn() && !CollectionUtils.isEmpty(history)
                && "user".equals(history.getLast().role())) {
            // 用户发了消息，但 Agent 还没来得及生成响应或触发任何 Checkpoint 就崩了
            // 补充一条错误提示作为 Assistant 的回答，避免上一条用户消息悬空
            history.add(AgentMessage.assistant("Error: task interrupted before a response was generated."));
        }

        // 场景三：清理状态并持久化
        if (checkpoint != null || state.pendingUserTurn()) {
            // 重新构造一个新的 AgentSessionState：
            // - 使用修正后的 history
            // - 将 checkpoint 清空为 null
            // - 将 pendingUserTurn 置为 false（说明这次未完成的 Turn 已经被补偿修补完毕）
            AgentSessionState restored = new AgentSessionState(history, null, false);
            // 保存最新的状态到持久化存储（如数据库/Redis）
            store.save(conversation, restored);
            return restored;
        }

        // 如果既没有 checkpoint 也没有 pendingUserTurn，说明状态很健康，无需调整，原样返回
        return state;
    }

    /**
     * 消息去重与安全拼接算法
     * 在把恢复出来的消息片段（recovered）追加到主历史记录（history）末尾时，自动找出并剔除两者的重叠（重复）部分，防止重复添加消息。
     */
    private void appendMissingSuffix(List<AgentMessage> history, List<AgentMessage> recovered) {
        int overlap = 0; // 用于记录 overlap（重叠元素）的个数
        // 1. 重叠部分的最大可能长度，不可能超过两个 List 中较短的那一个
        int max = Math.min(history.size(), recovered.size());

        // 2. 从可能的最大重叠长度开始，从大到小（贪心策略）倒序循环匹配
        for (int size = max; size > 0; size--) {
            // history 的最后 size 个元素 vs recovered 的最前 size 个元素
            if (history.subList(history.size() - size, history.size()).equals(recovered.subList(0, size))) {
                overlap = size; // 找到了最大重叠长度！
                break; // 匹配到了最长重叠，直接退出循环
            }
        }
        // 3. 截取 recovered 中跳过重叠部分后的子列表（即真正缺失的后缀），追加到 history 末尾
        history.addAll(recovered.subList(overlap, recovered.size()));
    }

    private Prompt buildPrompt(List<AgentMessage> history, List<String> toolsUsed) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(properties.getSystemPrompt()));
        // 仅保留最近的 getMaxHistoryMessages() 条消息。Math.max(0, ...) 则是为了防止数组下标越界（当历史记录不够长时从 0 开始）。
        // 作用：防止历史消息太长导致超出模型 Token 限制或增加费用。
        int start = Math.max(0, history.size() - properties.getMaxHistoryMessages());
        Set<String> declaredCalls = new HashSet<>();
        for (AgentMessage item : history.subList(start, history.size())) {
            switch (item.role()) {
                case "user" -> messages.add(new UserMessage(item.content()));
                case "system" -> messages.add(new SystemMessage(item.content()));
                case "assistant" -> {
                    // 使用 declaredCalls.add(c.id()) 集合记录模型发起的 Tool Call ID。这个设计非常关键（见下一步）。
                    List<AssistantMessage.ToolCall> calls = item.toolCalls() == null ? List.of()
                            : item.toolCalls().stream().map(c -> {
                        declaredCalls.add(c.id());
                        return new AssistantMessage.ToolCall(c.id(), c.type(), c.name(), c.arguments());
                    }).toList();
                    messages.add(AssistantMessage.builder().content(item.content() == null ? "" : item.content())
                            .toolCalls(calls).build());
                }
                case "tool" -> {
                    // 大多数大模型 API（如 OpenAI）对 Tool Calling 有严苛的格式约束：tool 类型的消息必须跟着它对应的 assistant 工具声明。
                    // 如果前面因为截断（History Truncation），导致发起的 assistant 消息被丢弃了，单独剩下一条 tool 结果，大模型 API 会直接报错。
                    // 这里利用 declaredCalls.contains(item.toolCallId()) 进行校验：只有当该工具调用的发起者（Assistant）在当前上下文窗口内存在时，才放入工具执行结果。
                    if (declaredCalls.contains(item.toolCallId())) {
                        messages.add(ToolResponseMessage.builder().responses(List.of(
                                new ToolResponseMessage.ToolResponse(item.toolCallId(), item.toolName(), item.content())
                        )).build());
                    }
                }
                default -> {
                }
            }
        }
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(tools.trackingCallbacks(toolsUsed::add))
                .build();
        return new Prompt(messages, options);
    }

    private void emitChunks(String text, Consumer<String> onDelta) {
        int size = Math.max(1, properties.getStreamChunkSize());
        for (int i = 0; i < text.length(); i += size) {
            onDelta.accept(text.substring(i, Math.min(text.length(), i + size)));
        }
    }
}
