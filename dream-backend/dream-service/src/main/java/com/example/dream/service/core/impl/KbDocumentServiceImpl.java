package com.example.dream.service.core.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.dream.dal.mapper.KbDocumentMapper;
import com.example.dream.dal.po.KbDocumentPO;
import com.example.dream.service.core.KbDocumentCoreService;
import org.springframework.stereotype.Service;

/**
 * 文档表 Service 实现类
 */
@Service
public class KbDocumentServiceImpl extends ServiceImpl<KbDocumentMapper, KbDocumentPO>
        implements KbDocumentCoreService {

}