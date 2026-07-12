package com.example.dream.web.controller;

import com.example.dream.common.context.UserContext;
import com.example.dream.common.vo.Result;
import com.example.dream.service.biz.ChatManageBizService;
import com.example.dream.service.biz.SessionBizService;
import com.example.dream.service.biz.bo.PageResultBO;
import com.example.dream.service.biz.bo.chat.DialogInfoBO;
import com.example.dream.service.biz.bo.chat.DialogSaveBO;
import com.example.dream.service.biz.bo.chat.SessionBO;
import com.example.dream.web.vo.chat.ChatListVO;
import com.example.dream.web.vo.chat.ChatSaveReqVO;
import com.example.dream.web.vo.chat.ChatVO;
import com.example.dream.web.vo.chat.DeleteReqVO;
import com.example.dream.web.vo.chat.SessionSaveReqVO;
import com.example.dream.web.vo.chat.SessionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 聊天助手（Chat）与会话（Session）相关接口，对齐 RagFlow /api/v1/chats 系列接口。
 *
 * <ul>
 *   <li>POST   /api/v1/chats                                 创建助手</li>
 *   <li>GET    /api/v1/chats                                 助手列表</li>
 *   <li>GET    /api/v1/chats/{chatId}                        助手详情</li>
 *   <li>PUT    /api/v1/chats/{chatId}                        更新助手</li>
 *   <li>DELETE /api/v1/chats                              删除助手</li>
 *   <li>POST   /api/v1/chats/{chatId}/sessions               创建会话</li>
 *   <li>GET    /api/v1/chats/{chatId}/sessions               会话列表</li>
 *   <li>GET    /api/v1/chats/{chatId}/sessions/{sessionId}   会话详情</li>
 *   <li>PATCH  /api/v1/chats/{chatId}/sessions/{sessionId}   重命名会话</li>
 *   <li>DELETE /api/v1/chats/{chatId}/sessions               删除会话</li>
 * </ul>
 *
 * @author dream
 */
@RestController
@RequestMapping("/api/v1/chats")
@RequiredArgsConstructor
public class ChatController {

    private final ChatManageBizService chatManageBizService;

    private final SessionBizService sessionBizService;

    // ==================== 助手（Chat） ====================

    /**
     * 创建助手，对应 RagFlow POST /chats。
     */
    @PostMapping
    public Result<ChatVO> create(@RequestBody ChatSaveReqVO req) {
        DialogInfoBO created = chatManageBizService.create(toSaveBO(req), UserContext.getUserId());
        return Result.success(toVO(created));
    }

    /**
     * 助手列表，对应 RagFlow GET /chats。
     */
    @GetMapping
    public Result<ChatListVO> list(@RequestParam(value = "name", required = false) String name,
                                   @RequestParam(value = "keywords", required = false) String keywords,
                                   @RequestParam(value = "page", defaultValue = "1") int page,
                                   @RequestParam(value = "page_size", defaultValue = "30") int pageSize,
                                   @RequestParam(value = "orderby", defaultValue = "create_time") String orderby,
                                   @RequestParam(value = "desc", defaultValue = "true") boolean desc) {
        PageResultBO<DialogInfoBO> result = chatManageBizService.list(
                name, keywords, page, pageSize, orderby, desc, UserContext.getUserId());
        ChatListVO vo = new ChatListVO();
        vo.setChats(result.getData().stream().map(this::toVO).toList());
        vo.setTotal(result.getTotal());
        return Result.success(vo);
    }

    /**
     * 助手详情，对应 RagFlow GET /chats/{chat_id}。
     */
    @GetMapping("/{chatId}")
    public Result<ChatVO> get(@PathVariable("chatId") Long chatId) {
        return Result.success(toVO(chatManageBizService.get(chatId, UserContext.getUserId())));
    }

    /**
     * 更新助手，对应 RagFlow PUT /chats/{chat_id}。
     */
    @PutMapping("/{chatId}")
    public Result<ChatVO> update(@PathVariable("chatId") Long chatId, @RequestBody ChatSaveReqVO req) {
        DialogInfoBO updated = chatManageBizService.update(chatId, toSaveBO(req), UserContext.getUserId());
        return Result.success(toVO(updated));
    }

    /**
     * 删除助手，对应 RagFlow DELETE /chats。
     */
    @DeleteMapping
    public Result<Integer> delete(@RequestBody DeleteReqVO req) {
        int count = chatManageBizService.delete(
                toLongIds(req.getIds()), Boolean.TRUE.equals(req.getDeleteAll()), UserContext.getUserId());
  return Result.success(count);
    }

    // ==================== 会话（Session） ====================

    /**
     * 创建会话，对应 RagFlow POST /chats/{chat_id}/sessions。
     */
    @PostMapping("/{chatId}/sessions")
    public Result<SessionVO> createSession(@PathVariable("chatId") Long chatId,
                                           @RequestBody(required = false) SessionSaveReqVO req) {
        String name = req == null ? null : req.getName();
        SessionBO session = sessionBizService.create(chatId, name, UserContext.getUserId());
        return Result.success(toSessionVO(session));
    }

    /**
     * 会话列表，对应 RagFlow GET /chats/{chat_id}/sessions。
     */
    @GetMapping("/{chatId}/sessions")
    public Result<List<SessionVO>> listSessions(@PathVariable("chatId") Long chatId,
                                                @RequestParam(value = "page", defaultValue = "1") int page,
                                                @RequestParam(value = "page_size", defaultValue = "30") int pageSize,
                                                @RequestParam(value = "orderby", defaultValue = "create_time") String orderby,
                                                @RequestParam(value = "desc", defaultValue = "true") boolean desc,
                                                @RequestParam(value = "name", required = false) String name) {
        List<SessionBO> sessions = sessionBizService.list(
                chatId, page, pageSize, orderby, desc, name, UserContext.getUserId());
        return Result.success(sessions.stream().map(this::toSessionVO).toList());
    }

    /**
     * 会话详情，对应 RagFlow GET /chats/{chat_id}/sessions/{session_id}。
     */
    @GetMapping("/{chatId}/sessions/{sessionId}")
    public Result<SessionVO> getSession(@PathVariable("chatId") Long chatId,
                                        @PathVariable("sessionId") Long sessionId) {
        return Result.success(toSessionVO(sessionBizService.get(chatId, sessionId, UserContext.getUserId())));
    }

    /**
     * 重命名会话，对应 RagFlow PATCH /chats/{chat_id}/sessions/{session_id}。
     */
    @PatchMapping("/{chatId}/sessions/{sessionId}")
    public Result<SessionVO> renameSession(@PathVariable("chatId") Long chatId,
                                           @PathVariable("sessionId") Long sessionId,
                                           @RequestBody SessionSaveReqVO req) {
        SessionBO session = sessionBizService.rename(chatId, sessionId, req.getName(), UserContext.getUserId());
        return Result.success(toSessionVO(session));
    }

    /**
     * 删除会话，对应 RagFlow DELETE /chats/{chat_id}/sessions。
     */
    @DeleteMapping("/{chatId}/sessions")
    public Result<Integer> deleteSessions(@PathVariable("chatId") Long chatId, @RequestBody DeleteReqVO req) {
        int count = sessionBizService.delete(
                chatId, toLongIds(req.getIds()), Boolean.TRUE.equals(req.getDeleteAll()), UserContext.getUserId());
        return Result.success(count);
    }

    // ==================== 转换 ====================

    private DialogSaveBO toSaveBO(ChatSaveReqVO req) {
        DialogSaveBO bo = new DialogSaveBO();
        bo.setName(req.getName());
        bo.setDescription(req.getDescription());
        bo.setLlmId(req.getLlmId());
        bo.setLlmSetting(req.getLlmSetting());
        bo.setPromptConfig(req.getPromptConfig());
        bo.setDatasetIds(toLongIds(req.getDatasetIds()));
        bo.setRerankId(req.getRerankId());
        bo.setTopN(req.getTopN());
        bo.setTopK(req.getTopK());
        bo.setSimilarityThreshold(req.getSimilarityThreshold());
        bo.setVectorSimilarityWeight(req.getVectorSimilarityWeight());
        return bo;
    }

    private ChatVO toVO(DialogInfoBO bo) {
        if (bo == null) {
            return null;
        }
        ChatVO vo = new ChatVO();
        vo.setId(bo.getId() == null ? null : String.valueOf(bo.getId()));
        vo.setName(bo.getName());
        vo.setDescription(bo.getDescription());
        vo.setUserId(bo.getUserId());
        vo.setLlmId(bo.getLlmId());
        vo.setLlmSetting(bo.getLlmSetting());
        vo.setPromptConfig(bo.getPromptConfig());
        vo.setDatasetIds(bo.getDatasetIds() == null ? null
                : bo.getDatasetIds().stream().map(String::valueOf).toList());
        vo.setKbNames(bo.getKbNames());
        vo.setRerankId(bo.getRerankId());
        vo.setTopN(bo.getTopN());
        vo.setTopK(bo.getTopK());
        vo.setSimilarityThreshold(bo.getSimilarityThreshold());
        vo.setVectorSimilarityWeight(bo.getVectorSimilarityWeight());
        vo.setCreatedTime(bo.getCreatedTime());
        vo.setModifiedTime(bo.getModifiedTime());
        return vo;
    }

    private SessionVO toSessionVO(SessionBO bo) {
        if (bo == null) {
            return null;
        }
        SessionVO vo = new SessionVO();
        vo.setId(bo.getId() == null ? null : String.valueOf(bo.getId()));
        vo.setChatId(bo.getChatId() == null ? null : String.valueOf(bo.getChatId()));
        vo.setUserId(bo.getUserId());
        vo.setName(bo.getName());
        vo.setMessages(bo.getMessages());
        vo.setReference(bo.getReference());
        vo.setCreatedTime(bo.getCreatedTime());
        vo.setModifiedTime(bo.getModifiedTime());
        return vo;
    }

    /**
     * 字符串 ID 列表转 Long 列表（前端为避免精度丢失以字符串传输）。
     */
    private List<Long> toLongIds(List<String> ids) {
        if (ids == null) {
            return null;
        }
        return ids.stream().filter(s -> s != null && !s.isBlank()).map(Long::valueOf).toList();
    }
}