package com.example.dream.web.config;

import com.example.dream.service.biz.AuthBizService;
import com.example.dream.web.interceptor.LoginInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：CORS 跨域 + 登录拦截器注册
 *
 * @author dream
 */
@Configuration
@RequiredArgsConstructor
public class WebCorsConfig implements WebMvcConfigurer {

    private final AuthBizService authBizService;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor(authBizService))
                // 拦截所有 /api 接口
                .addPathPatterns("/api/**")
                // 放行登录、退出等无需鉴权的接口
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/logout",
                        "/api/hello"
                );
    }
}