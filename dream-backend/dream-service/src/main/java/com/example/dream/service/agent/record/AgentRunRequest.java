package com.example.dream.service.agent.record;

/**
 * Immutable turn admission snapshot.
 */
public record AgentRunRequest(Long dialogId, Long conversationId, String userId, String modelKey, String userText) {
}
