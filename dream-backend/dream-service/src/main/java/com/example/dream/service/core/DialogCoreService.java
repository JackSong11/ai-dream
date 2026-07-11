package com.example.dream.service.core;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.dream.dal.po.ChatDialogPO;

/**
 * 对话（Dialog）核心领域服务。
 *
 * <p>对应 RagFlow DialogService，提供对话的基础持久化与查询能力。</p>
 */
public interface DialogCoreService extends IService<ChatDialogPO> {

    /**
     * 校验并获取归属当前用户的有效对话。
     *
     * <p>对应 RagFlow _ensure_owned_chat / DialogService.query(tenant_id, id, status=VALID)。</p>
     *
     * @param dialogId 对话 ID
     * @param userId 归属用户 ID
     * @return 命中的有效对话，未命中返回 null
     */
    ChatDialogPO getOwnedValidDialog(Long dialogId, String userId);

}