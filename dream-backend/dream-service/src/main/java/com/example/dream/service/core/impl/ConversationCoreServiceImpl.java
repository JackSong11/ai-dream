package com.example.dream.service.core.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.dream.dal.mapper.ConversationMapper;
import com.example.dream.dal.po.ChatConversationPO;
import com.example.dream.service.core.ConversationCoreService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * {@link ConversationCoreService} 实现。
 */
@Service
public class ConversationCoreServiceImpl extends ServiceImpl<ConversationMapper, ChatConversationPO>
        implements ConversationCoreService {

}