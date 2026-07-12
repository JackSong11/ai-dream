package com.example.dream.service.biz;

import com.example.dream.service.biz.bo.PageResultBO;
import com.example.dream.service.biz.bo.chat.DialogInfoBO;
import com.example.dream.service.biz.bo.chat.DialogSaveBO;

import java.util.List;

/**
 * 聊天助手（Chat / Dialog）管理业务编排服务。
 *
 * <p>对应 RagFlow chat_api.py 的 /chats 系列接口：助手的创建、列表、详情、更新、删除。
 * 鉴权使用当前登录用户 userId（替代 RagFlow tenant_id）。</p>
*
 * @author dream
 */
public interface ChatManageBizService {

    /**
     * 创建聊天助手，对应 RagFlow POST /chats（create）。
     *
     * @param save   创建请求
     * @param userId 当前登录用户 ID
     * @return 创建成功的助手详情
     */
    DialogInfoBO create(DialogSaveBO save, String userId);

    /**
     * 分页查询聊天助手列表，对应 RagFlow GET /chats（list_chats）。
     *
     * @param name     名称精确过滤（可空）
     * @param keywords 名称关键字模糊过滤（可空）
     * @param page     页码
     * @param pageSize 每页大小（0 表示不分页）
     * @param orderby  排序字段（create_time / update_time）
     * @param desc     是否降序
     * @param userId   当前登录用户 ID
     * @return 分页结果
     */
    PageResultBO<DialogInfoBO> list(String name, String keywords, int page, int pageSize,
                                    String orderby, boolean desc, String userId);

    /**
     * 查询聊天助手详情，对应 RagFlow GET /chats/{chat_id}（get_chat）。
     *
     * @param chatId 助手 ID
     * @param userId 当前登录用户 ID
     * @return 助手详情
     */
    DialogInfoBO get(Long chatId, String userId);

    /**
     * 更新聊天助手，对应 RagFlow PUT /chats/{chat_id}（update_chat）。
     *
     * @param chatId 助手 ID
     * @param save   更新请求（字段为 null 表示不更新）
     * @param userId 当前登录用户 ID
     * @return 更新后的助手详情
     */
    DialogInfoBO update(Long chatId, DialogSaveBO save, String userId);

    /**
     * 删除聊天助手（逻辑删除），对应 RagFlow DELETE /chats（bulk_delete_chats）。
     *
     * @param ids       待删除 ID 列表
     * @param deleteAll ids 为空时是否删除当前用户全部助手
     * @param userId    当前登录用户 ID
     * @return 成功删除数量
     */
    int delete(List<Long> ids, boolean deleteAll, String userId);
}