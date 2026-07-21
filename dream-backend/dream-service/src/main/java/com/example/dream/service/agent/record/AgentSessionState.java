package com.example.dream.service.agent.record;

import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable conversation state stored in chat_conversation.message/reference.
 */
public record AgentSessionState(List<AgentMessage> messages, RuntimeCheckpoint checkpoint,
                                boolean pendingUserTurn) {
    public AgentSessionState {
        messages = CollectionUtils.isEmpty(messages) ? new ArrayList<>() : new ArrayList<>(messages);
    }
}
