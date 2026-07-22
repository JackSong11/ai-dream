package com.example.dream.service.agent;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Combines local annotated tools and tools supplied by MCP providers. */
@Component
public class AgentToolRegistry {
    private final List<ToolCallback> callbacks;
    private final ObservationRegistry observationRegistry;

    public AgentToolRegistry(AgentBuiltinTools builtins, ObjectProvider<ToolCallbackProvider> providers,
                             ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
        Map<String, ToolCallback> unique = new LinkedHashMap<>();
        Arrays.stream(ToolCallbacks.from(builtins))
                .forEach(cb -> unique.put(cb.getToolDefinition().name(), cb));
        providers.orderedStream().flatMap(p -> Arrays.stream(p.getToolCallbacks()))
                .forEach(cb -> unique.putIfAbsent(cb.getToolDefinition().name(), cb));
        this.callbacks = List.copyOf(new ArrayList<>(unique.values()));
    }

    /**
     * 为一次 Agent 调用创建带使用记录的工具回调，由 ChatClient 的
     * ToolCallingAdvisor 负责执行完整工具调用循环。
     */
    public List<ToolCallback> trackingCallbacks(Consumer<String> onToolCalled) {
        return callbacks.stream().map(delegate -> (ToolCallback) new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                return observeToolCall(delegate, onToolCalled, () -> delegate.call(toolInput));
            }

            @Override
            public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
                return observeToolCall(delegate, onToolCalled, () -> delegate.call(toolInput, toolContext));
            }
        }).toList();
    }

    private String observeToolCall(ToolCallback delegate, Consumer<String> onToolCalled,
                                   java.util.function.Supplier<String> invocation) {
        String toolName = delegate.getToolDefinition().name();
        onToolCalled.accept(toolName);
        return Observation.createNotStarted("dream.agent.tool", observationRegistry)
                .contextualName("tool " + toolName)
                .lowCardinalityKeyValue("gen_ai.tool.name", toolName)
                .observe(invocation);
    }

}
