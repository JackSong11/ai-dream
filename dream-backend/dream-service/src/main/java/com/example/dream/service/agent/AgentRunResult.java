package com.example.dream.service.agent;

import java.util.List;

public record AgentRunResult(String answer, List<String> toolsUsed, String stopReason, int iterations) {
}
