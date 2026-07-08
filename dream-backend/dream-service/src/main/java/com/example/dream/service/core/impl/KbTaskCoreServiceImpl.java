package com.example.dream.service.core.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.dream.dal.mapper.KbTaskMapper;
import com.example.dream.dal.po.KbTaskPO;
import com.example.dream.service.core.KbTaskCoreService;
import org.springframework.stereotype.Service;

/**
 * 文档解析任务表 Service 实现类
 */
@Service
public class KbTaskCoreServiceImpl extends ServiceImpl<KbTaskMapper, KbTaskPO>
        implements KbTaskCoreService {

}