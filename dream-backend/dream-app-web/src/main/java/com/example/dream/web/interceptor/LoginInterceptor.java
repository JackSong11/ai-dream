package com.example.dream.web.interceptor;

import com.example.dream.common.context.UserContext;
import com.example.dream.service.biz.AuthBizService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录拦截器：解析请求头中的 token，写入 {@link UserContext}。
 * <p>token 无效或缺失时返回 401，业务代码可直接通过 UserContext 获取当前 userId。</p>
 *
 * @author dream
 */
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    private static final String HEADER_TOKEN = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthBizService authBizService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 放行 CORS 预检请求（OPTIONS），否则带 Authorization 头的跨域请求预检会被拦截返回 401
        // 真奇怪，这个还真不能去掉，不然访问不通，解决不了跨域
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String token = resolveToken(request);
        String userId = authBizService.getUserIdByToken(token);
        if (!StringUtils.hasText(userId)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        UserContext.setUserId(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 请求结束清理 ThreadLocal，防止线程复用串号
        UserContext.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_TOKEN);
        if (!StringUtils.hasText(header)) {
            return null;
        }
        if (header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return header.trim();
    }
}