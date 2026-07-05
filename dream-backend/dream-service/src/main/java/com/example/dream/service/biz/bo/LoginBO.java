package com.example.dream.service.biz.bo;

import lombok.Data;

import java.io.Serializable;

/**
 * 登录结果业务对象
 *
 * @author dream
 */
@Data
public class LoginBO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 登录凭证 token
     */
    private String token;

    /**
     * 登录账号
     */
    private String userId;

    /**
     * 用户角色
     */
    private String role;

    /**
     * 头像地址
     */
    private String avatarUrl;
}