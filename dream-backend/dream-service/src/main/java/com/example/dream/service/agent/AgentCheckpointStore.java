package com.example.dream.service.agent;

import com.example.dream.common.exception.BizException;
import com.example.dream.dal.po.ChatConversationPO;
import com.example.dream.service.agent.record.AgentMessage;
import com.example.dream.service.agent.record.AgentSessionState;
import com.example.dream.service.agent.record.RuntimeCheckpoint;
import com.example.dream.service.core.ConversationCoreService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists messages and checkpoint on the existing conversation row.
 */
@Component
@RequiredArgsConstructor
public class AgentCheckpointStore {

    private final ConversationCoreService conversations;
    private final ObjectMapper objectMapper;

    public ChatConversationPO requireOwned(Long id, Long dialogId, String userId) {
        ChatConversationPO po = conversations.lambdaQuery()
                .eq(ChatConversationPO::getId, id)
                .eq(ChatConversationPO::getDialogId, dialogId)
                .eq(ChatConversationPO::getUserId, userId)
                .one();
        if (po == null) throw new BizException("会话不存在或无权访问");
        return po;
    }

    public AgentSessionState load(ChatConversationPO po) {
        try {
            List<AgentMessage> messages = StringUtils.isBlank(po.getMessage()) ? new ArrayList<>() : objectMapper.readValue(po.getMessage(), new TypeReference<>() {
            });
            RuntimeCheckpoint checkpoint = null;
            boolean pending = false;
            if (StringUtils.isNotBlank(po.getReference())) {
                JsonNode root = objectMapper.readTree(po.getReference());
                if (root.isObject() && root.has("agentRuntime")) {
                    JsonNode runtime = root.get("agentRuntime");
                    pending = runtime.path("pendingUserTurn").asBoolean(false);
                    if (runtime.hasNonNull("checkpoint")) {
                        checkpoint = objectMapper.treeToValue(runtime.get("checkpoint"), RuntimeCheckpoint.class);
                    }
                }
            }
            return new AgentSessionState(messages, checkpoint, pending);
        } catch (Exception e) {
            throw new BizException("会话历史损坏，无法恢复: " + e.getMessage());
        }
    }

    public void save(ChatConversationPO po, AgentSessionState state) {
        try {
            // 序列化消息历史 (messages)
            po.setMessage(objectMapper.writeValueAsString(state.messages()));
            // 构建运行时状态元数据 (agentRuntime)
            var root = objectMapper.createObjectNode();
            var runtime = root.putObject("agentRuntime");
            runtime.put("pendingUserTurn", state.pendingUserTurn());
            if (state.checkpoint() != null) runtime.set("checkpoint", objectMapper.valueToTree(state.checkpoint()));
            po.setReference(objectMapper.writeValueAsString(root));
            if (!conversations.updateById(po)) throw new BizException("保存 Agent 会话失败");
            /*
            {
              "agentRuntime": {
                "pendingUserTurn": true,
                "checkpoint": {
                  "phase": "AWAITING_TOOLS",
                  "assistantMessage": { ... },
                  "pendingToolCalls": [
                    { "id": "call_123", "name": "get_weather" }
                  ]
                }
              }
            }
             */
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("保存 Agent 会话失败: " + e.getMessage());
        }
    }
}
