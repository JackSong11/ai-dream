package com.example.dream.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.dream.common.context.UserContext;
import com.example.dream.common.exception.BizException;
import com.example.dream.common.vo.Result;
import com.example.dream.dal.po.ChatConversationPO;
import com.example.dream.dal.po.ChatDialogPO;
import com.example.dream.service.core.ConversationCoreService;
import com.example.dream.service.core.DialogCoreService;
import com.example.dream.service.core.ai.registry.ChatClientRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal chat/session HTTP API used by the Vue assistant. */
@RestController
@RequestMapping("/api/v1/chats")
@RequiredArgsConstructor
public class ChatController {

    private final DialogCoreService dialogs;
    private final ConversationCoreService conversations;
    private final ChatClientRegistry chatClientRegistry;
    private final ObjectMapper objectMapper;

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String userId = UserContext.getUserId();
        ChatDialogPO po = new ChatDialogPO();
        po.setName(text(body.get("name"), "新对话"));
        po.setDescription(text(body.get("description"), null));
        po.setUserId(userId);
        po.setLlmId(text(body.get("llmId"), chatClientRegistry.getDefaultModelKey()));
        po.setLlmSetting("{}");
        po.setPromptConfig("{}");
        po.setKbIds(json(body.getOrDefault("datasetIds", List.of())));
        po.setTopN(6);
        po.setTopK(1024);
        po.setSimilarityThreshold(0.2);
        po.setVectorSimilarityWeight(0.3);
        dialogs.save(po);
        return Result.success(dialogView(po));
    }

    @GetMapping
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(name = "page_size", defaultValue = "50") long pageSize,
                                            @RequestParam(defaultValue = "") String keywords) {
        LambdaQueryWrapper<ChatDialogPO> query = new LambdaQueryWrapper<ChatDialogPO>()
                .eq(ChatDialogPO::getUserId, UserContext.getUserId())
                .orderByDesc(ChatDialogPO::getModifiedTime);
        if (StringUtils.hasText(keywords)) query.like(ChatDialogPO::getName, keywords);
        Page<ChatDialogPO> result = dialogs.page(Page.of(page, pageSize), query);
        return Result.success(Map.of("chats", result.getRecords().stream().map(this::dialogView).toList(),
                "total", result.getTotal()));
    }

    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        ChatDialogPO po = requireDialog(id);
        if (body.containsKey("name")) po.setName(text(body.get("name"), po.getName()));
        if (body.containsKey("description")) po.setDescription(text(body.get("description"), null));
        if (body.containsKey("datasetIds")) po.setKbIds(json(body.get("datasetIds")));
        if (body.containsKey("llmId")) po.setLlmId(text(body.get("llmId"), po.getLlmId()));
        dialogs.updateById(po);
        return Result.success(dialogView(po));
    }

    @DeleteMapping
    public Result<Integer> delete(@RequestBody Map<String, Object> body) {
        List<Long> ids = longList(body.get("ids"));
        if (ids.isEmpty()) return Result.success(0);
        List<Long> owned = dialogs.lambdaQuery().eq(ChatDialogPO::getUserId, UserContext.getUserId())
                .in(ChatDialogPO::getId, ids).list().stream().map(ChatDialogPO::getId).toList();
        dialogs.removeByIds(owned);
        return Result.success(owned.size());
    }

    @PostMapping("/{dialogId}/sessions")
    public Result<Map<String, Object>> createSession(@PathVariable Long dialogId,
                                                     @RequestBody Map<String, Object> body) {
        requireDialog(dialogId);
        ChatConversationPO po = new ChatConversationPO();
        po.setDialogId(dialogId);
        po.setUserId(UserContext.getUserId());
        po.setName(text(body.get("name"), "New session"));
        po.setMessage("[]");
        po.setReference("{}");
        conversations.save(po);
        return Result.success(sessionView(po));
    }

    @GetMapping("/{dialogId}/sessions")
    public Result<List<Map<String, Object>>> sessions(@PathVariable Long dialogId,
                                                      @RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(name = "page_size", defaultValue = "50") long pageSize) {
        requireDialog(dialogId);
        Page<ChatConversationPO> result = conversations.page(Page.of(page, pageSize),
                new LambdaQueryWrapper<ChatConversationPO>()
                        .eq(ChatConversationPO::getDialogId, dialogId)
                        .eq(ChatConversationPO::getUserId, UserContext.getUserId())
                        .orderByDesc(ChatConversationPO::getModifiedTime));
        return Result.success(result.getRecords().stream().map(this::sessionView).toList());
    }

    @GetMapping("/{dialogId}/sessions/{sessionId}")
    public Result<Map<String, Object>> session(@PathVariable Long dialogId, @PathVariable Long sessionId) {
        return Result.success(sessionView(requireSession(dialogId, sessionId)));
    }

    @PatchMapping("/{dialogId}/sessions/{sessionId}")
    public Result<Map<String, Object>> renameSession(@PathVariable Long dialogId, @PathVariable Long sessionId,
                                                     @RequestBody Map<String, Object> body) {
        ChatConversationPO po = requireSession(dialogId, sessionId);
        po.setName(text(body.get("name"), po.getName()));
        conversations.updateById(po);
        return Result.success(sessionView(po));
    }

    @DeleteMapping("/{dialogId}/sessions")
    public Result<Integer> deleteSessions(@PathVariable Long dialogId, @RequestBody Map<String, Object> body) {
        requireDialog(dialogId);
        List<Long> ids = longList(body.get("ids"));
        if (ids.isEmpty()) return Result.success(0);
        List<Long> owned = conversations.lambdaQuery().eq(ChatConversationPO::getDialogId, dialogId)
                .eq(ChatConversationPO::getUserId, UserContext.getUserId())
                .in(ChatConversationPO::getId, ids).list().stream()
                .map(ChatConversationPO::getId).toList();
        conversations.removeByIds(owned);
        return Result.success(owned.size());
    }

    private ChatDialogPO requireDialog(Long id) {
        ChatDialogPO po = dialogs.getOwnedValidDialog(id, UserContext.getUserId());
        if (po == null) throw new BizException("对话不存在或无权访问");
        return po;
    }

    private ChatConversationPO requireSession(Long dialogId, Long id) {
        ChatConversationPO po = conversations.lambdaQuery().eq(ChatConversationPO::getId, id)
                .eq(ChatConversationPO::getDialogId, dialogId)
                .eq(ChatConversationPO::getUserId, UserContext.getUserId()).one();
        if (po == null) throw new BizException("会话不存在或无权访问");
        return po;
    }

    private Map<String, Object> dialogView(ChatDialogPO po) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(po.getId()));
        result.put("name", po.getName());
        result.put("description", po.getDescription());
        result.put("userId", po.getUserId());
        result.put("llmId", po.getLlmId());
        result.put("llmSetting", tree(po.getLlmSetting(), objectMapper.createObjectNode()));
        result.put("promptConfig", tree(po.getPromptConfig(), objectMapper.createObjectNode()));
        result.put("datasetIds", tree(po.getKbIds(), objectMapper.createArrayNode()));
        result.put("kbNames", List.of());
        result.put("rerankId", po.getRerankId());
        result.put("topN", po.getTopN());
        result.put("topK", po.getTopK());
        result.put("similarityThreshold", po.getSimilarityThreshold());
        result.put("vectorSimilarityWeight", po.getVectorSimilarityWeight());
        result.put("createdTime", po.getCreatedTime());
        result.put("modifiedTime", po.getModifiedTime());
        return result;
    }

    private Map<String, Object> sessionView(ChatConversationPO po) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(po.getId()));
        result.put("chatId", String.valueOf(po.getDialogId()));
        result.put("userId", po.getUserId());
        result.put("name", po.getName());
        result.put("messages", publicMessages(po.getMessage()));
        result.put("reference", List.of());
        result.put("createdTime", po.getCreatedTime());
        result.put("modifiedTime", po.getModifiedTime());
        return result;
    }

    private List<JsonNode> publicMessages(String json) {
        JsonNode root = tree(json, objectMapper.createArrayNode());
        List<JsonNode> visible = new ArrayList<>();
        if (root.isArray()) root.forEach(node -> {
            String role = node.path("role").asText();
            if ((role.equals("user") || role.equals("assistant") || role.equals("system"))
                    && !node.path("hidden").asBoolean(false)) visible.add(node);
        });
        return visible;
    }

    private JsonNode tree(String json, JsonNode fallback) {
        if (!StringUtils.hasText(json)) return fallback;
        try { return objectMapper.readTree(json); } catch (Exception ignored) { return fallback; }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BizException("JSON 参数无效"); }
    }

    private String text(Object value, String fallback) {
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? fallback : String.valueOf(value);
    }

    private List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).map(Long::valueOf).toList();
    }
}
