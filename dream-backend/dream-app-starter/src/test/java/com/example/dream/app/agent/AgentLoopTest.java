package com.example.dream.app.agent;

import com.example.dream.dal.po.ChatConversationPO;
import com.example.dream.dal.po.ChatDialogPO;
import com.example.dream.integration.service.redis.lock.DistributedLockService;
import com.example.dream.service.agent.AgentCheckpointStore;
import com.example.dream.service.agent.AgentLoop;
import com.example.dream.service.agent.record.AgentRunRequest;
import com.example.dream.service.agent.record.AgentRunResult;
import com.example.dream.service.agent.record.AgentRuntimeProperties;
import com.example.dream.service.agent.record.AgentSessionState;
import com.example.dream.service.agent.AgentToolRegistry;
import com.example.dream.service.agent.AgentToolExecutionRuntime;
import com.example.dream.service.core.DialogCoreService;
import com.example.dream.service.core.ai.registry.ChatClientRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLoopTest {
    private AgentCheckpointStore store;
    private AgentToolRegistry tools;
    private ChatClient client;
    private ChatClient.CallResponseSpec responseSpec;
    private ChatClient.StreamResponseSpec streamResponseSpec;
    private DistributedLockService distributedLockService;
    private DistributedLockService.LockHandle lockHandle;
    private AgentLoop loop;

    @BeforeEach
    void setUp() {
        ChatClientRegistry chatClientRegistry = mock(ChatClientRegistry.class);
        DialogCoreService dialogs = mock(DialogCoreService.class);
        store = mock(AgentCheckpointStore.class);
        tools = mock(AgentToolRegistry.class);
        client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        responseSpec = mock(ChatClient.CallResponseSpec.class);
        streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        distributedLockService = mock(DistributedLockService.class);
        lockHandle = mock(DistributedLockService.LockHandle.class);
        properties.setStreamChunkSize(2);
        ChatDialogPO dialog = new ChatDialogPO();
        dialog.setId(10L);
        dialog.setUserId("ethan");
        dialog.setLlmId("test-model");
        ChatConversationPO conversation = new ChatConversationPO();
        conversation.setId(20L);
        conversation.setDialogId(10L);
        conversation.setUserId("ethan");
        when(dialogs.getOwnedValidDialog(10L, "ethan")).thenReturn(dialog);
        when(store.requireOwned(20L, 10L, "ethan")).thenReturn(conversation);
        when(store.load(conversation)).thenReturn(new AgentSessionState(new ArrayList<>(), null, false));
        when(chatClientRegistry.getClient("test-model")).thenReturn(client);
        when(client.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(tools.trackingCallbacks(any())).thenReturn(List.of());
        when(distributedLockService.acquire("dream:lock:agent:conversation:20")).thenReturn(lockHandle);
        loop = new AgentLoop(chatClientRegistry, dialogs, store, tools, properties, distributedLockService,
                new AgentToolExecutionRuntime(), ObservationRegistry.NOOP);
    }

    @Test
    void streamsAndPersistsFinalResponse() {
        when(streamResponseSpec.chatResponse()).thenReturn(Flux.just(
                response(AssistantMessage.builder().content("你好").build()),
                response(AssistantMessage.builder().content("呀").build())));
        List<String> deltas = new ArrayList<>();
        AgentRunResult result = loop.run(new AgentRunRequest(10L, 20L, "ethan", null, "你好"), deltas::add);
        assertThat(result.answer()).isEqualTo("你好呀");
        assertThat(deltas).containsExactly("你好", "呀");
        verify(store, org.mockito.Mockito.atLeast(3)).save(any(), any());
        verify(distributedLockService).acquire("dream:lock:agent:conversation:20");
        verify(lockHandle).close();
    }

    @Test
    void delegatesToolLoopToChatClient() {
        when(tools.trackingCallbacks(any())).thenAnswer(invocation -> {
            java.util.function.Consumer<String> recorder = invocation.getArgument(0);
            recorder.accept("clock");
            return List.<ToolCallback>of();
        });
        when(responseSpec.chatResponse()).thenReturn(response(AssistantMessage.builder().content("现在是十点").build()));
        AgentRunResult result = loop.run(new AgentRunRequest(10L, 20L, "ethan", null, "几点了"), null);
        assertThat(result.answer()).isEqualTo("现在是十点");
        assertThat(result.toolsUsed()).containsExactly("clock");
        assertThat(result.iterations()).isEqualTo(1);
        verify(client).prompt(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    private ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }
}
