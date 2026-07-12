package com.example.dream.service.biz.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.dream.common.enums.base.ResCodeEnum;
import com.example.dream.common.exception.BizException;
import com.example.dream.dal.mapper.DialogMapper;
import com.example.dream.dal.mapper.KnowledgeBaseMapper;
import com.example.dream.dal.po.ChatDialogPO;
import com.example.dream.dal.po.KnowledgeBasePO;
import com.example.dream.service.biz.ChatManageBizService;
import com.example.dream.service.biz.bo.PageResultBO;
import com.example.dream.service.biz.bo.chat.DialogInfoBO;
import com.example.dream.service.biz.bo.chat.DialogSaveBO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ChatManageBizService} 实现，对齐 RagFlow chat_api.py 的 /chats 系列逻辑。
 *
 * <p>Dialog 的复杂字段（llm_setting / prompt_config / kb_ids）以 JSON 字符串存于 PO，
 * 本类负责与 BO 之间的序列化转换。鉴权使用登录用户 userId 对齐 RagFlow tenant_id。</p>
 *
 * @author dream
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatManageBizServiceImpl implements ChatManageBizService {

    /**
     * 助手名称长度上限（字节），对应 RagFlow _validate_name 中 255 限制。
     */
    private static final int CHAT_NAME_LIMIT = 255;

    /**
     * 默认 prompt_config，对应 RagFlow _DEFAULT_PROMPT_CONFIG。
     */
    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are an intelligent assistant. Please summarize the content of the dataset to answer the question. "
                    + "Please list the data in the dataset and answer in detail. When all dataset content is irrelevant "
                    + "to the question, your answer must include the sentence \"The answer you are looking for is not "
                    + "found in the dataset!\" Answers need to consider chat history.\n"
                    + "      Here is the knowledge base:\n      {knowledge}\n      The above is the knowledge base.";

    private static final String DEFAULT_PROLOGUE = "Hi! I'm your assistant. What can I do for you?";

    private final DialogMapper dialogMapper;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final ObjectMapper objectMapper;

    // ==================== create ====================

    @Override
    public DialogInfoBO create(DialogSaveBO save, String userId) {
        // 对应 Python: _validate_name(required=True)
        String name = validateName(save == null ? null : save.getName(), true);

        // 对应 Python: dataset_ids 校验（这里做归属校验）
        List<Long> kbIds = validateDatasetIds(save.getDatasetIds(), userId);

        // 对应 Python: DialogService.query 去重
        if (existsSameName(name, userId, null)) {
            throw new BizException(ResCodeEnum.DATA_ERROR, "Duplicated chat name in creating chat.");
        }

        ChatDialogPO po = new ChatDialogPO();
        po.setId(IdWorker.getId());
        po.setName(name);
        // 对应 Python: req.setdefault("description", "A helpful Assistant")
        po.setDescription(StringUtils.hasText(save.getDescription()) ? save.getDescription() : "A helpful Assistant");
        po.setUserId(userId);
        po.setLlmId(save.getLlmId() == null ? "" : save.getLlmId());
        po.setLlmSetting(writeJson(save.getLlmSetting() == null ? new HashMap<>() : save.getLlmSetting()));
        po.setPromptConfig(writeJson(applyPromptDefaults(save.getPromptConfig(), kbIds)));
        po.setKbIds(writeJson(kbIds));
        po.setRerankId(save.getRerankId() == null ? "" : save.getRerankId());
        // 对应 Python: 默认 top_n=6 / top_k=1024 / similarity_threshold=0.1 / vector_similarity_weight=0.3
        po.setTopN(save.getTopN() == null ? 6 : save.getTopN());
        po.setTopK(save.getTopK() == null ? 1024 : save.getTopK());
        po.setSimilarityThreshold(save.getSimilarityThreshold() == null ? 0.1 : save.getSimilarityThreshold());
        po.setVectorSimilarityWeight(save.getVectorSimilarityWeight() == null ? 0.3 : save.getVectorSimilarityWeight());
        po.setCreator(userId);

        if (dialogMapper.insert(po) <= 0) {
            throw new BizException(ResCodeEnum.DATA_ERROR, "Failed to create chat.");
        }
        return toBO(dialogMapper.selectById(po.getId()));
    }

    // ==================== list ====================

    @Override
    public PageResultBO<DialogInfoBO> list(String name, String keywords, int page, int pageSize,
                                           String orderby, boolean desc, String userId) {
        LambdaQueryWrapper<ChatDialogPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatDialogPO::getUserId, userId);
        if (StringUtils.hasText(name)) {
            wrapper.eq(ChatDialogPO::getName, name);
        } else if (StringUtils.hasText(keywords)) {
            // 对应 Python: chat_id or name 存在时 keywords 置空；否则按 keywords 模糊
            wrapper.like(ChatDialogPO::getName, keywords);
        }
        applyOrder(wrapper, orderby, desc);

        // 对应 Python: page/page_size 为 0 时不分页
        if (page <= 0 || pageSize <= 0) {
            List<ChatDialogPO> all = dialogMapper.selectList(wrapper);
            List<DialogInfoBO> data = new ArrayList<>(all.size());
            for (ChatDialogPO po : all) {
                data.add(toBO(po));
            }
            return new PageResultBO<>(data, all.size());
        }

        Page<ChatDialogPO> pageParam = new Page<>(page, pageSize);
        IPage<ChatDialogPO> result = dialogMapper.selectPage(pageParam, wrapper);
        List<DialogInfoBO> data = new ArrayList<>();
        for (ChatDialogPO po : result.getRecords()) {
            data.add(toBO(po));
        }
        return new PageResultBO<>(data, result.getTotal());
    }

    // ==================== get ====================

    @Override
    public DialogInfoBO get(Long chatId, String userId) {
        ChatDialogPO po = ensureOwnedChat(chatId, userId);
        return toBO(po);
    }

    // ==================== update ====================

    @Override
    public DialogInfoBO update(Long chatId, DialogSaveBO save, String userId) {
        ChatDialogPO current = ensureOwnedChat(chatId, userId);

        ChatDialogPO update = new ChatDialogPO();
        update.setId(chatId);

        if (save.getName() != null) {
            String name = validateName(save.getName(), true);
            // 对应 Python: name 变更时去重校验
            if (!name.equalsIgnoreCase(current.getName()) && existsSameName(name, userId, chatId)) {
                throw new BizException(ResCodeEnum.DATA_ERROR, "Duplicated chat name.");
            }
            update.setName(name);
        }
        if (save.getDescription() != null) {
            update.setDescription(save.getDescription());
        }
        if (save.getLlmId() != null) {
            update.setLlmId(save.getLlmId());
        }
        if (save.getLlmSetting() != null) {
            update.setLlmSetting(writeJson(save.getLlmSetting()));
        }
        if (save.getPromptConfig() != null) {
            update.setPromptConfig(writeJson(save.getPromptConfig()));
        }
        if (save.getDatasetIds() != null) {
            List<Long> kbIds = validateDatasetIds(save.getDatasetIds(), userId);
            update.setKbIds(writeJson(kbIds));
        }
        if (save.getRerankId() != null) {
            update.setRerankId(save.getRerankId());
        }
        if (save.getTopN() != null) {
            update.setTopN(save.getTopN());
        }
        if (save.getTopK() != null) {
            update.setTopK(save.getTopK());
        }
        if (save.getSimilarityThreshold() != null) {
            update.setSimilarityThreshold(save.getSimilarityThreshold());
        }
        if (save.getVectorSimilarityWeight() != null) {
            update.setVectorSimilarityWeight(save.getVectorSimilarityWeight());
        }

        if (dialogMapper.updateById(update) <= 0) {
            throw new BizException(ResCodeEnum.DATA_ERROR, "Failed to update chat.");
        }
        return toBO(dialogMapper.selectById(chatId));
    }

    // ==================== delete ====================

    @Override
    public int delete(List<Long> ids, boolean deleteAll, String userId) {
        // 对应 Python: ids 为空且 delete_all=True 时删除全部
        if (CollectionUtils.isEmpty(ids)) {
            if (!deleteAll) {
                return 0;
            }
            LambdaQueryWrapper<ChatDialogPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChatDialogPO::getUserId, userId);
            ids = dialogMapper.selectList(wrapper).stream().map(ChatDialogPO::getId).toList();
            if (CollectionUtils.isEmpty(ids)) {
                return 0;
            }
        }

        int success = 0;
        for (Long id : ids) {
            // 对应 Python: _ensure_owned_chat 校验归属后逻辑删除
            LambdaQueryWrapper<ChatDialogPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChatDialogPO::getId, id).eq(ChatDialogPO::getUserId, userId);
            if (dialogMapper.selectCount(wrapper) == 0) {
                continue;
            }
            success += dialogMapper.deleteById(id);
        }
        return success;
    }

    // ==================== 私有工具 ====================

    /**
     * 校验助手是否归属当前用户，对应 RagFlow _ensure_owned_chat。
     */
    private ChatDialogPO ensureOwnedChat(Long chatId, String userId) {
        if (chatId == null) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "`chat_id` is required.");
        }
        LambdaQueryWrapper<ChatDialogPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatDialogPO::getId, chatId).eq(ChatDialogPO::getUserId, userId);
        ChatDialogPO po = dialogMapper.selectOne(wrapper);
        if (po == null) {
            throw new BizException(ResCodeEnum.UNAUTHORIZED, "No authorization.");
        }
        return po;
    }

    /**
     * 名称校验，对应 RagFlow _validate_name。
     */
    private String validateName(String name, boolean required) {
        if (name == null) {
            if (required) {
                throw new BizException(ResCodeEnum.PARAMETER_ERROR, "`name` is required.");
            }
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "`name` is required.");
        }
        int bytes = trimmed.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > CHAT_NAME_LIMIT) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR,
                    "Chat name length is " + bytes + " which is larger than " + CHAT_NAME_LIMIT + ".");
        }
        return trimmed;
    }

    /**
     * 同名去重校验，对应 RagFlow DialogService.query(name=..., tenant_id=...)。
     */
    private boolean existsSameName(String name, String userId, Long excludeId) {
        LambdaQueryWrapper<ChatDialogPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatDialogPO::getName, name).eq(ChatDialogPO::getUserId, userId);
        if (excludeId != null) {
            wrapper.ne(ChatDialogPO::getId, excludeId);
        }
        return dialogMapper.selectCount(wrapper) > 0;
    }

    /**
     * 知识库归属校验，对应 RagFlow _validate_dataset_ids（简化：仅校验归属存在）。
     */
    private List<Long> validateDatasetIds(List<Long> datasetIds, String userId) {
        if (CollectionUtils.isEmpty(datasetIds)) {
            return new ArrayList<>();
        }
        List<Long> normalized = new ArrayList<>();
        for (Long id : datasetIds) {
            if (id == null) {
                continue;
            }
            LambdaQueryWrapper<KnowledgeBasePO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(KnowledgeBasePO::getId, id).eq(KnowledgeBasePO::getUserId, userId);
            if (knowledgeBaseMapper.selectCount(wrapper) == 0) {
                throw new BizException(ResCodeEnum.PARAMETER_ERROR, "You don't own the dataset " + id);
            }
            normalized.add(id);
        }
        return normalized;
    }

    /**
     * 补齐 prompt_config 默认值，对应 RagFlow _apply_prompt_defaults。
     */
    private Map<String, Object> applyPromptDefaults(Map<String, Object> promptConfig, List<Long> kbIds) {
        Map<String, Object> config = promptConfig == null ? new HashMap<>() : new HashMap<>(promptConfig);
        Object system = config.get("system");
        if (system == null || !StringUtils.hasText(String.valueOf(system))) {
            config.put("system", DEFAULT_SYSTEM_PROMPT);
        }
        config.putIfAbsent("prologue", DEFAULT_PROLOGUE);
        config.putIfAbsent("empty_response", "Sorry! No relevant content was found in the knowledge base!");
        config.putIfAbsent("quote", Boolean.TRUE);
        config.putIfAbsent("refine_multiturn", Boolean.TRUE);
        return config;
    }

    // ==================== 转换 ====================

    private DialogInfoBO toBO(ChatDialogPO po) {
        if (po == null) {
            return null;
        }
        DialogInfoBO bo = new DialogInfoBO();
        bo.setId(po.getId());
        bo.setName(po.getName());
        bo.setDescription(po.getDescription());
        bo.setUserId(po.getUserId());
        bo.setLlmId(po.getLlmId());
        bo.setLlmSetting(readMap(po.getLlmSetting()));
        bo.setPromptConfig(readMap(po.getPromptConfig()));
        List<Long> kbIds = readLongList(po.getKbIds());
        bo.setDatasetIds(kbIds);
        bo.setKbNames(resolveKbNames(kbIds));
        bo.setRerankId(po.getRerankId());
        bo.setTopN(po.getTopN());
        bo.setTopK(po.getTopK());
        bo.setSimilarityThreshold(po.getSimilarityThreshold());
        bo.setVectorSimilarityWeight(po.getVectorSimilarityWeight());
        bo.setCreatedTime(po.getCreatedTime());
        bo.setModifiedTime(po.getModifiedTime());
        return bo;
    }

    /**
     * 解析知识库名称，对应 RagFlow _resolve_kb_names。
     */
    private List<String> resolveKbNames(List<Long> kbIds) {
        List<String> names = new ArrayList<>();
        if (CollectionUtils.isEmpty(kbIds)) {
            return names;
        }
        for (Long id : kbIds) {
            KnowledgeBasePO kb = knowledgeBaseMapper.selectById(id);
            if (kb != null) {
                names.add(kb.getName());
            }
        }
        return names;
    }

    private void applyOrder(LambdaQueryWrapper<ChatDialogPO> wrapper, String orderby, boolean desc) {
        if ("update_time".equals(orderby)) {
            wrapper.orderBy(true, !desc, ChatDialogPO::getModifiedTime);
        } else {
            wrapper.orderBy(true, !desc, ChatDialogPO::getCreatedTime);
        }
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BizException(ResCodeEnum.SERVER_ERROR, "序列化失败: " + e.getMessage());
        }
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("readMap failed: {}", json, e);
            return new HashMap<>();
        }
    }

    private List<Long> readLongList(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            log.warn("readLongList failed: {}", json, e);
            return new ArrayList<>();
        }
    }
}