package com.example.dream.app.agent;

import com.example.dream.service.agent.AgentToolExecutionRuntime;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolCallingAdvisorTest {

    @Test
    void routesToolExecutionThroughAgentRuntime() {
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "clock", "{}")))
                .build();
        assertThat(response(toolCall).hasToolCalls()).isTrue();
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return modelCalls.getAndIncrement() == 0
                        ? response(toolCall)
                        : response(AssistantMessage.builder().content("现在是 10:00").build());
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            @Override
            public ChatOptions getOptions() {
                return getDefaultOptions();
            }
        };
        ToolCallback clock = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("clock")
                        .description("Get the current time")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String input) {
                return "10:00";
            }
        };

        AgentToolExecutionRuntime runtime = new AgentToolExecutionRuntime();
        List<String> boundaries = new ArrayList<>();
        AgentToolExecutionRuntime.BoundaryListener listener = new AgentToolExecutionRuntime.BoundaryListener() {
            @Override
            public void awaiting(int iteration, AssistantMessage assistant) {
                boundaries.add("awaiting:" + iteration);
            }

            @Override
            public void completed(int iteration, AssistantMessage assistant, ToolResponseMessage response,
                                  List<Message> conversationHistory) {
                boundaries.add("completed:" + iteration + ":" + response.getResponses().getFirst().responseData());
            }
        };

        ChatClient client = ChatClient.builder(model, ObservationRegistry.NOOP, null, null,
                        ToolCallingAdvisor.builder().toolCallingManager(runtime))
                .build();
        String answer;
        try (AgentToolExecutionRuntime.Scope ignored = runtime.open(3, listener)) {
            answer = client.prompt()
                    .user("几点了？")
                    .toolCallbacks(clock)
                    .call()
                    .content();
        }

        assertThat(modelCalls).hasValue(2);
        assertThat(boundaries).containsExactly("awaiting:1", "completed:1:10:00");
        assertThat(answer).isEqualTo("现在是 10:00");
    }

    private ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }
}
