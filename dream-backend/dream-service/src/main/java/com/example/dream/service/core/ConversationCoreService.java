package com.example.dream.service.core;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.dream.dal.po.ChatConversationPO;

/**
 * 会话（Conversation）核心领域服务。
 *
 * <p>对应 RagFlow ConversationService，提供会话的基础持久化与查询能力。</p>
 */
public interface ConversationCoreService extends IService<ChatConversationPO> {

}