package com.example.dream.service.core.ai.agent.runner;

import com.example.dream.service.core.ai.agent.hook.AgentHook;
import com.example.dream.service.core.ai.agent.mcp.McpToolProvider;
import com.example.dream.service.core.ai.agent.message.AgentMessage;
import com.example.dream.service.core.ai.registry.ChatModelRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.metadata.ToolMetadata;import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 迭代运行器，对应 nanobot agent.runner.AgentRunner。
 * <p>
 * <b>用 Spring AI 2.0 内建的 ChatClient 多轮工具循环承载 nanobot 手写迭代：</b>
 * <ul>
 *   <li>多轮工具调用：{@code ChatClient} 内建 ToolCallingManager 自动完成
 *       「模型请求工具 → 框架执行工具 → 回填结果 → 再次请求模型」的迭代；</li>
 *   <li>工具使用采集：用 {@link CountingToolCallback} 包装每个 ToolCallback，
 *       在工具真正被调用时记录 tools_used 并触发 {@link AgentHook#onToolResult}；</li>
 *   <li><b>MCP 工具</b>：通过 {@link McpToolProvider} 合并 Spring AI MCP 暴露的
 *       ToolCallback，与本地 @Tool 一起交给模型，调用时在同一工具循环里同步执行；</li>
 *   <li>停止原因：正常产出=stop，空回复=empty_final_response，异常=error；</li>
 *   <li>流式：{@code .stream().chatResponse()} 逐 delta 回调 onStream。</li>
 * </ul>
 *
 * @author dream
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunner {

    private final ChatModelRegistry registry;

    private final McpToolProvider mcpToolProvider;

    /**
     * 运行一次 Agent 回合（同步或流式，由 spec.stream 决定）。
     */
    public AgentRunResult run(AgentRunSpec spec) {
        spec.getHooks().forEach(h -> h.onTurnStart(spec.getInitialMessages()));
        Set<String> toolsUsed = Collections.newSetFromMap(new ConcurrentHashMap<>());
        try {
            AgentRunResult result = spec.isStream()
                    ? runStream(spec, toolsUsed) : runSync(spec, toolsUsed);
            result.setToolsUsed(new ArrayList<>(toolsUsed));
            spec.getHooks().forEach(h -> h.onTurnEnd(result.getFinalContent(), result.getStopReason()));
            return result;
        } catch (Exception ex) {
            log.error("[AgentRunner] 运行异常, sessionKey={}", spec.getSessionKey(), ex);
            return AgentRunResult.builder()
                    .finalContent("抱歉，调用 AI 模型时发生错误：" + ex.getMessage())
                    .stopReason("error")
                    .build();
        }
    }

    /**
     * 同步运行：ChatClient 内部自动完成多轮工具调用循环。
     */
    private AgentRunResult runSync(AgentRunSpec spec, Set<String> toolsUsed) {
        ChatClient client = registry.getClient(spec.getModelKey());
        ChatClient.ChatClientRequestSpec request = client.prompt()
                .messages(toSpringMessages(spec.getInitialMessages()));
        List<ToolCallback> callbacks = resolveToolCallbacks(spec, toolsUsed);
        if (!callbacks.isEmpty()) {
            // Spring AI 2.0 规范写法：工具经 ToolCallingChatOptions 传入，
            // 替代 ChatClientRequestSpec 上已废弃/待删除的 toolCallbacks(...)。
            request = request.options(ToolCallingChatOptions.builder()
                    .toolCallbacks(callbacks));
        }
        String content = request.call().content();        return buildResult(content);
    }

    /**
     * 流式运行：逐 delta 回调 onStream，结束时回调 onStreamEnd。
     * ChatClient 的流式同样内建工具调用处理。
     */
    private AgentRunResult runStream(AgentRunSpec spec, Set<String> toolsUsed) {
        ChatClient client = registry.getClient(spec.getModelKey());
        ChatClient.ChatClientRequestSpec request = client.prompt()
                .messages(toSpringMessages(spec.getInitialMessages()));
        List<ToolCallback> callbacks = resolveToolCallbacks(spec, toolsUsed);
        if (!callbacks.isEmpty()) {
            // Spring AI 2.0 规范写法：工具经 ToolCallingChatOptions 传入，
            // 替代 ChatClientRequestSpec 上已废弃/待删除的 toolCallbacks(...)。
            request = request.options(ToolCallingChatOptions.builder()
                    .toolCallbacks(callbacks));
        }

        StringBuilder buffer = new StringBuilder();
        request.stream().chatResponse()
                .doOnNext(resp -> {
                    String delta = extractText(resp);
                    if (delta != null && !delta.isEmpty()) {
                        buffer.append(delta);
                        spec.getCallbacks().stream(delta);
                    }
                })
                .blockLast();
        spec.getCallbacks().streamEnd(false);
        return buildResult(buffer.toString());
    }

    /**
     * 由最终文本组装运行结果（含空回复判定与 assistant 消息回写）。
     */
    private AgentRunResult buildResult(String content) {
        boolean empty = content == null || content.isBlank();
        List<AgentMessage> produced = new ArrayList<>();
        produced.add(AgentMessage.of("assistant", content == null ? "" : content));
        return AgentRunResult.builder()
                .finalContent(content)
                .messages(produced)
                .stopReason(empty ? "empty_final_response" : "stop")
                .build();
    }

    /**
     * 汇总本回合可用工具：本地 @Tool 对象 + MCP 工具，统一用计数包装器包裹以采集工具使用。
     */
    private List<ToolCallback> resolveToolCallbacks(AgentRunSpec spec, Set<String> toolsUsed) {
        List<ToolCallback> raw = new ArrayList<>();
        // 1. 本地 @Tool 对象
        if (spec.getTools() != null && spec.getTools().length > 0) {
            ToolCallback[] local = MethodToolCallbackProvider.builder()
                    .toolObjects(spec.getTools())
                    .build()
                    .getToolCallbacks();
            Collections.addAll(raw, local);
        }
        // 2. MCP 工具（未配置时返回空）
        raw.addAll(mcpToolProvider.getToolCallbacks());

        // 3. 统一包装计数
        List<ToolCallback> wrapped = new ArrayList<>(raw.size());
        for (ToolCallback cb : raw) {
            wrapped.add(new CountingToolCallback(cb, toolsUsed, spec.getHooks()));
        }
        return wrapped;
    }

    /**
     * 从单个流式响应块提取文本内容。
     */
    private String extractText(ChatResponse resp) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return null;
        }
        return resp.getResult().getOutput().getText();
    }

    /**
     * 将 AgentMessage 列表转换为 Spring AI 的 Message 列表（含 tool 结构还原）。
     */
    private List<Message> toSpringMessages(List<AgentMessage> messages) {
        List<Message> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        for (AgentMessage m : messages) {
            String role = m.getRole() == null ? "user" : m.getRole();
            String content = m.getContent() == null ? "" : m.getContent();
            switch (role) {
                case "system" -> result.add(new SystemMessage(content));
                case "assistant" -> result.add(toAssistantMessage(m, content));
                case "tool" -> result.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                safe(m.getToolCallId()), safe(m.getName()), content)))
                        .build());
                default -> result.add(new UserMessage(content));
            }
        }
        return result;
    }

    /**
     * 还原带 tool_calls 的 AssistantMessage（用于历史回放）。
     */
    private AssistantMessage toAssistantMessage(AgentMessage m, String content) {
        if (CollectionUtils.isEmpty(m.getToolCalls())) {
            return new AssistantMessage(content);
        }
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (AgentMessage.ToolCall tc : m.getToolCalls()) {
            calls.add(new AssistantMessage.ToolCall(
                    safe(tc.getId()), "function", safe(tc.getName()), safe(tc.getArguments())));
        }
        return AssistantMessage.builder().content(content).toolCalls(calls).build();
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 计数型 ToolCallback 包装器：委托真实工具执行（本地或 MCP），并在调用时
     * 采集 tools_used 与触发 {@link AgentHook#onToolResult}，对应 nanobot 工具使用统计。
     */
    private record CountingToolCallback(ToolCallback delegate, Set<String> toolsUsed,
                                        List<AgentHook> hooks) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return record(delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return record(delegate.call(toolInput, toolContext));
        }

        private String record(String result) {
            String name = delegate.getToolDefinition().name();
            toolsUsed.add(name);
            hooks.forEach(h -> h.onToolResult(name, result));
            return result;
        }
    }
}