package com.example.dream.web.controller;

import com.example.dream.common.context.UserContext;
import com.example.dream.common.vo.Result;
import com.example.dream.service.biz.AuthBizService;
import com.example.dream.service.biz.bo.LoginBO;
import com.example.dream.web.vo.LoginReqVO;
import com.example.dream.web.vo.LoginRespVO;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 认证相关接口：登录、退出、获取当前登录用户
 *
 * @author dream
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthBizService authBizService;

    /**
     * 账号密码登录
     */
    @PostMapping("/login")
    public Result<LoginRespVO> login(@RequestBody LoginReqVO req) {
        LoginBO bo = authBizService.login(req.getUserId(), req.getPassword());
        LoginRespVO vo = new LoginRespVO();
        vo.setToken(bo.getToken());
        vo.setUserId(bo.getUserId());
        vo.setRole(bo.getRole());
        vo.setAvatarUrl(bo.getAvatarUrl());
        return Result.success("登录成功", vo);
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authBizService.logout(resolveToken(authorization));
        return Result.success();
    }

    /**
     * 获取当前登录用户（需登录，演示 UserContext 的使用）
     */
    @GetMapping("/current")
    public Result<String> current() {
        return Result.success(UserContext.getUserId());
    }

    private String resolveToken(String authorization) {
        if (!StringUtils.hasText(authorization)) {
            return null;
        }
        if (authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return authorization.trim();
    }
}