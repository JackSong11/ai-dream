package com.example.dream.service.core.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.dream.dal.mapper.BizUserMapper;
import com.example.dream.dal.po.BizUserPO;
import com.example.dream.service.core.BizUserCoreService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 用户表 Service 实现类
 */
@Service
public class BizUserServiceImpl extends ServiceImpl<BizUserMapper, BizUserPO>
        implements BizUserCoreService {

    @Override
    public BizUserPO getByUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        return this.getOne(Wrappers.<BizUserPO>lambdaQuery()
                .eq(BizUserPO::getUserId, userId)
                .last("limit 1"));
    }
}