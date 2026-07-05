package com.example.dream.service.biz;

import com.example.dream.service.biz.bo.LoginBO;

/**
 * 认证业务编排服务
 *
 * @author dream
 */
public interface AuthBizService {

    /**
     * 账号密码登录
     *
     * @param userId   登录账号
     * @param password 登录密码（明文）
     * @return 登录结果（含 token），失败时抛出异常
     */
    LoginBO login(String userId, String password);

    /**
     * 根据 token 获取登录账号
     *
     * @param token 登录凭证
     * @return 登录账号，无效返回 null
     */
    String getUserIdByToken(String token);

    /**
     * 退出登录
     *
     * @param token 登录凭证
     */
    void logout(String token);
}