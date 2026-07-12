package com.example.dream.service.biz;

import com.example.dream.service.biz.bo.chat.SessionBO;

import java.util.List;

/**
 * 会话（Session / Conversation）管理业务编排服务。
 *
 * <p>对应 RagFlow chat_api.py 的 /chats/{chat_id}/sessions 系列接口：会话的创建、
 * 列表、详情、重命名、删除。鉴权使用当前登录用户 userId。</p>
 *
 * @author dream
 */
public interface SessionBizService {

    /**
     * 创建会话，对应 RagFlow POST /chats/{chat_id}/sessions（create_session）。
     *
     * <p>新会话会以助手 prompt_config.prologue 作为首条 assistant 开场白。</p>
     *
     * @param chatId 助手 ID
     * @param name   会话名称（可空，默认 "New session"）
     * @param userId 当前登录用户 ID
     * @return 创建成功的会话
     */
    SessionBO create(Long chatId, String name, String userId);

    /**
     * 查询会话列表，对应 RagFlow GET /chats/{chat_id}/sessions（list_sessions）。
     *
     * @param chatId   助手 ID
     * @param page     页码
     * @param pageSize 每页大小
     * @param orderby  排序字段（create_time / update_time）
     * @param desc     是否降序
     * @param name     名称过滤（可空）
     * @param userId   当前登录用户 ID
     * @return 会话列表
     */
    List<SessionBO> list(Long chatId, int page, int pageSize, String orderby, boolean desc,
                         String name, String userId);

    /**
     * 查询会话详情，对应 RagFlow GET /chats/{chat_id}/sessions/{session_id}（get_session）。
     *
     * @param chatId    助手 ID
     * @param sessionId 会话 ID
     * @param userId    当前登录用户 ID
     * @return 会话详情
     */
    SessionBO get(Long chatId, Long sessionId, String userId);

    /**
     * 重命名会话，对应 RagFlow PATCH /chats/{chat_id}/sessions/{session_id}（update_session）。
     *
     * @param chatId    助手 ID
     * @param sessionId 会话 ID
     * @param name      新名称
     * @param userId    当前登录用户 ID
     * @return 更新后的会话
     */
    SessionBO rename(Long chatId, Long sessionId, String name, String userId);

    /**
     * 删除会话，对应 RagFlow DELETE /chats/{chat_id}/sessions（delete_sessions）。
     *
     * @param chatId    助手 ID
     * @param ids       待删除会话 ID 列表
     * @param deleteAll ids 为空时是否删除该助手全部会话
     * @param userId    当前登录用户 ID
     * @return 成功删除数量
     */
    int delete(Long chatId, List<Long> ids, boolean deleteAll, String userId);
}