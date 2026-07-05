package com.example.dream.service.core.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.dream.dal.mapper.BizUserMapper;
import com.example.dream.dal.po.BizUserPO;
import com.example.dream.service.core.BizUserCoreService;
import org.springframework.stereotype.Service;

/**
 * 用户表 Service 实现类
 */
@Service
public class BizUserServiceImpl extends ServiceImpl<BizUserMapper, BizUserPO>
        implements BizUserCoreService {

}