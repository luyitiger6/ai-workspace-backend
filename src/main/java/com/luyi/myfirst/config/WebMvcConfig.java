package com.luyi.myfirst.config;

import com.luyi.myfirst.web.auth.interceptor.AuthInterceptor;
import com.luyi.myfirst.web.auth.interceptor.OriginGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器的装配点。
 *
 * 顺序在这里是契约而不是实现细节：来源校验（{@link OriginGuard#ORDER}）必须早于
 * 凭证判定（{@link AuthInterceptor#ORDER}）。反过来的话，一个跨站请求会先撞 401，
 * spec 要求的 403 + CROSS_SITE_REJECTED 就永远观察不到，而两道防护都「看起来在」。
 *
 * 认证拦截器按「默认保护 + 排除公开」注册：新增的 /api/auth/** 端点默认受保护，
 * 忘记把公开端点加进排除清单只会得到 401（显性、测试立刻发现），
 * 而不是悄悄少了一道保护。下面六个公开路径来自 design.md 的端点表。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final OriginGuard originGuard;
    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(OriginGuard originGuard, AuthInterceptor authInterceptor) {
        this.originGuard = originGuard;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(originGuard)
                .addPathPatterns("/api/**")
                .order(OriginGuard.ORDER);

        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/auth/**")
                .excludePathPatterns(
                        "/api/auth/captcha",
                        "/api/auth/register-code",
                        "/api/auth/register",
                        "/api/auth/password-reset-code",
                        "/api/auth/password-reset",
                        "/api/auth/login")
                .order(AuthInterceptor.ORDER);
    }
}
