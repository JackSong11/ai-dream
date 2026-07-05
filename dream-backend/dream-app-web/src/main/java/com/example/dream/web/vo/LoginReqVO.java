package com.example.dream.web.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 登录请求 VO
 *
 * @author dream
 */
@Data
public class LoginReqVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 登录账号（对应 biz_user.user_id）
     */
    private String userId;

    /**
     * 登录密码（明文，由后端加密后比对）
     */
    private String password;
}