package com.example.dream.web.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 登录响应 VO
 *
 * @author dream
 */
@Data
public class LoginRespVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 登录凭证 token，后续请求需放入请求头 Authorization
     */
    private String token;

    /**
     * 登录用户账号
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