package com.example.dream.service.biz.impl;

import com.example.dream.common.enums.base.ResCodeEnum;
import com.example.dream.common.exception.BizException;
import com.example.dream.dal.po.BizUserPO;
import com.example.dream.integration.service.redis.RedisService;
import com.example.dream.service.biz.AuthBizService;
import com.example.dream.service.biz.bo.LoginBO;
import com.example.dream.service.core.BizUserCoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * 认证业务编排服务实现
 *
 * @author dream
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthBizServiceImpl implements AuthBizService {

    /**
     * Redis 中 token -> userId 的 key 前缀
     */
    private static final String TOKEN_KEY_PREFIX = "dream:login:token:";

    /**
     * token 有效期：2 小时
     */
    private static final Duration TOKEN_EXPIRE = Duration.ofHours(2);

    private final BizUserCoreService bizUserCoreService;

    private final RedisService redisService;

    @Override
    public LoginBO login(String userId, String password) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(password)) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "账号或密码不能为空");
        }

        BizUserPO user = bizUserCoreService.getByUserId(userId);
        if (user == null) {
            throw new BizException(ResCodeEnum.UNAUTHORIZED, "账号或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(ResCodeEnum.UNAUTHORIZED, "账号已被禁用");
        }

        String inputHash = md5(password);
        if (!inputHash.equalsIgnoreCase(user.getPasswordHash())) {
            throw new BizException(ResCodeEnum.UNAUTHORIZED, "账号或密码错误");
        }

        // 生成 token 并写入 Redis
        String token = UUID.randomUUID().toString().replace("-", "");
        redisService.set(TOKEN_KEY_PREFIX + token, user.getUserId(), TOKEN_EXPIRE);

        LoginBO bo = new LoginBO();
        bo.setToken(token);
        bo.setUserId(user.getUserId());
        bo.setRole(user.getRole());
        bo.setAvatarUrl(user.getAvatarUrl());
        return bo;
    }

    @Override
    public String getUserIdByToken(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        Object userId = redisService.get(TOKEN_KEY_PREFIX + token);
        return userId == null ? null : userId.toString();
    }

    @Override
    public void logout(String token) {
        if (StringUtils.hasText(token)) {
            redisService.delete(TOKEN_KEY_PREFIX + token);
        }
    }

    private String md5(String raw) {
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }
}