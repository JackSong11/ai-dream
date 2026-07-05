package com.example.dream.service.core;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.dream.dal.po.BizUserPO;

/**
 * 用户表 Service 接口
 */
public interface BizUserCoreService extends IService<BizUserPO> {

    /**
     * 根据登录账号查询用户
     *
     * @param userId 登录账号（biz_user.user_id）
     * @return 用户 PO，不存在返回 null
     */
    BizUserPO getByUserId(String userId);
}