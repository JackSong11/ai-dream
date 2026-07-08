package com.example.dream.service.core.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.dream.dal.mapper.KnowledgeBaseMapper;
import com.example.dream.dal.po.KnowledgeBasePO;
import com.example.dream.service.core.KnowledgeBaseCoreService;
import org.springframework.stereotype.Service;

/**
 * 知识库表 Service 实现类
 */
@Service
public class KnowledgeBaseCoreServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBasePO>
        implements KnowledgeBaseCoreService {

}