package com.example.dream.service.biz.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.dream.common.enums.base.ResCodeEnum;
import com.example.dream.common.exception.BizException;
import com.example.dream.dal.mapper.ConversationMapper;
import com.example.dream.dal.mapper.DialogMapper;
import com.example.dream.dal.po.ChatConversationPO;
import com.example.dream.dal.po.ChatDialogPO;
import com.example.dream.service.biz.SessionBizService;
import com.example.dream.service.biz.bo.chat.SessionBO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SessionBizService} 实现，对齐 RagFlow chat_api.py 的 /sessions 系列逻辑。
 *
 * <p>Conversation 的 message / reference 以 JSON 字符串存于 PO，本类负责序列化转换。
 * dialog_id 对外映射为 chat_id，message 对外映射为 messages（对应 _build_session_response）。</p>
 *
 * @author dream
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionBizServiceImpl implements SessionBizService {

    private static final int SESSION_NAME_LIMIT = 255;

    private static final String DEFAULT_SESSION_NAME = "New session";

    private final ConversationMapper conversationMapper;

    private final DialogMapper dialogMapper;

    private final ObjectMapper objectMapper;

    // ==================== create ====================

    @Override
    public SessionBO create(Long chatId, String name, String userId) {
        ChatDialogPO dialog = ensureOwnedChat(chatId, userId);

        // 对应 Python: name 默认 "New session"，非空校验，截断 255
        String sessionName = StringUtils.hasText(name) ? name.trim() : DEFAULT_SESSION_NAME;
        if (sessionName.length() > SESSION_NAME_LIMIT) {
            sessionName = sessionName.substring(0, SESSION_NAME_LIMIT);
        }

        // 对应 Python: 首条 assistant 消息使用 prompt_config.prologue
        String prologue = resolvePrologue(dialog);
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> first = new HashMap<>();
        first.put("role", "assistant");
        first.put("content", prologue);
        messages.add(first);

        ChatConversationPO po = new ChatConversationPO();
        po.setId(IdWorker.getId());
        po.setDialogId(chatId);
        po.setUserId(userId);
        po.setName(sessionName);
        po.setMessage(writeJson(messages));
        po.setReference(writeJson(new ArrayList<>()));
        po.setCreator(userId);

        if (conversationMapper.insert(po) <= 0) {
            throw new BizException(ResCodeEnum.DATA_ERROR, "Fail to create a session!");
        }
        return toBO(conversationMapper.selectById(po.getId()));
    }

    // ==================== list ====================

    @Override
    public List<SessionBO> list(Long chatId, int page, int pageSize, String orderby, boolean desc,
                                String name, String userId) {
        ensureOwnedChat(chatId, userId);

        // 对应 Python: page_size == 0 返回空列表
        if (pageSize == 0) {
            return new ArrayList<>();
        }

        LambdaQueryWrapper<ChatConversationPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatConversationPO::getDialogId, chatId);
        if (StringUtils.hasText(name)) {
            wrapper.eq(ChatConversationPO::getName, name);
        }
        applyOrder(wrapper, orderby, desc);

        List<ChatConversationPO> records = conversationMapper.selectList(wrapper);
        // 内存分页，对应 Python ConversationService.get_list 的 paginate
        if (page > 0 && pageSize > 0) {
            int start = (page - 1) * pageSize;
            if (start >= records.size()) {
                return new ArrayList<>();
            }
            int end = Math.min(start + pageSize, records.size());
            records = records.subList(start, end);
        }

        List<SessionBO> result = new ArrayList<>(records.size());
        for (ChatConversationPO po : records) {
            result.add(toBO(po));
        }
        return result;
    }

    // ==================== get ====================

    @Override
    public SessionBO get(Long chatId, Long sessionId, String userId) {
        ensureOwnedChat(chatId, userId);
        ChatConversationPO po = conversationMapper.selectById(sessionId);
        if (po == null) {
            throw new BizException(ResCodeEnum.DATA_NOT_EXIST, "Session not found!");
        }
        // 对应 Python: conv.dialog_id != chat_id 校验
        if (!chatId.equals(po.getDialogId())) {
            throw new BizException(ResCodeEnum.DATA_ERROR, "Session does not belong to this chat!");
        }
        return toBO(po);
    }

    // ==================== rename ====================

    @Override
    public SessionBO rename(Long chatId, Long sessionId, String name, String userId) {
        ensureOwnedChat(chatId, userId);
        ChatConversationPO current = conversationMapper.selectById(sessionId);
        if (current == null || !chatId.equals(current.getDialogId())) {
            throw new BizException(ResCodeEnum.DATA_NOT_EXIST, "Session not found!");
        }
        // 对应 Python: name 非空校验，截断 255
        if (!StringUtils.hasText(name)) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "`name` can not be empty.");
        }
        String newName = name.trim();
        if (newName.length() > SESSION_NAME_LIMIT) {
            newName = newName.substring(0, SESSION_NAME_LIMIT);
        }

        ChatConversationPO update = new ChatConversationPO();
        update.setId(sessionId);
        update.setName(newName);
        if (conversationMapper.updateById(update) <= 0) {
            throw new BizException(ResCodeEnum.DATA_ERROR, "Fail to update a session!");
        }
        return toBO(conversationMapper.selectById(sessionId));
    }

    // ==================== delete ====================

    @Override
    public int delete(Long chatId, List<Long> ids, boolean deleteAll, String userId) {
        ensureOwnedChat(chatId, userId);

        // 对应 Python: ids 为空且 delete_all=True 时删除该助手全部会话
        if (CollectionUtils.isEmpty(ids)) {
            if (!deleteAll) {
                return 0;
            }
            LambdaQueryWrapper<ChatConversationPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChatConversationPO::getDialogId, chatId);
            ids = conversationMapper.selectList(wrapper).stream().map(ChatConversationPO::getId).toList();
            if (CollectionUtils.isEmpty(ids)) {
                return 0;
            }
        }

        int success = 0;
        for (Long id : ids) {
            // 对应 Python: 校验会话归属该助手后删除
            LambdaQueryWrapper<ChatConversationPO> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ChatConversationPO::getId, id).eq(ChatConversationPO::getDialogId, chatId);
            if (conversationMapper.selectCount(wrapper) == 0) {
                continue;
            }
            success += conversationMapper.deleteById(id);
        }
        return success;
    }

    // ==================== 私有工具 ====================

    /**
     * 校验助手归属，对应 RagFlow _ensure_owned_chat。
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
     * 取助手开场白，对应 RagFlow dia.prompt_config.get("prologue", "")。
     */
    private String resolvePrologue(ChatDialogPO dialog) {
        Map<String, Object> promptConfig = readMap(dialog.getPromptConfig());
        Object prologue = promptConfig.get("prologue");
        return prologue == null ? "" : String.valueOf(prologue);
    }

    private void applyOrder(LambdaQueryWrapper<ChatConversationPO> wrapper, String orderby, boolean desc) {
        if ("update_time".equals(orderby)) {
            wrapper.orderBy(true, !desc, ChatConversationPO::getModifiedTime);
        } else {
            wrapper.orderBy(true, !desc, ChatConversationPO::getCreatedTime);
        }
    }

    // ==================== 转换 ====================

    private SessionBO toBO(ChatConversationPO po) {
        if (po == null) {
            return null;
        }
        SessionBO bo = new SessionBO();
        bo.setId(po.getId());
        // 对应 Python: dialog_id -> chat_id
        bo.setChatId(po.getDialogId());
        bo.setUserId(po.getUserId());
        bo.setName(po.getName());
        // 对应 Python: message -> messages
        bo.setMessages(readMessages(po.getMessage()));
        bo.setReference(readList(po.getReference()));
        bo.setCreatedTime(po.getCreatedTime());
        bo.setModifiedTime(po.getModifiedTime());
        return bo;
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

    private List<Map<String, Object>> readMessages(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            log.warn("readMessages failed: {}", json, e);
            return new ArrayList<>();
        }
    }

    private List<Object> readList(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            log.warn("readList failed: {}", json, e);
            return new ArrayList<>();
        }
    }
}