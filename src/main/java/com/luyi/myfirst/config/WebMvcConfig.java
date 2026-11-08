package com.luyi.myfirst.config;

import com.luyi.myfirst.web.auth.interceptor.AuthInterceptor;
import com.luyi.myfirst.web.auth.interceptor.OriginGuard;
import com.luyi.myfirst.web.auth.interceptor.WriteRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器的装配点。
 *
 * 顺序在这里是契约而不是实现细节，三层分别是：
 * <ol>
 *   <li>来源校验（{@link OriginGuard#ORDER}）：跨站请求直接 403；</li>
 *   <li>写限流（{@link WriteRateLimitInterceptor#ORDER}）：让跨站请求先被拒、不消耗额度，
 *       又让未登录的匿名写请求也受限，而不是先撞 401 再无限重试；</li>
 *   <li>凭证判定（{@link AuthInterceptor#ORDER}）：最后才看「你是谁」。</li>
 * </ol>
 * 顺序反过来的话，一个跨站请求会先撞 401，spec 要求的 403 + CROSS_SITE_REJECTED
 * 就永远观察不到，而三道防护都「看起来在」。
 *
 * 认证拦截器按「全站默认保护 + 代码内公开路由表」注册（design D3）：
 * {@code addPathPatterns}/{@code excludePathPatterns} 没有方法维度，表达不了
 * 「同一个 {@code /api/posts} 的 GET 公开、POST 受保护」，所以排除清单已整体移进
 * {@link AuthInterceptor} 的公开路由表，这里只负责覆盖范围。
 * 新增端点忘记登记的结果是 401（显性、测试立刻发现），而不是悄悄公开可写。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final OriginGuard originGuard;
    private final WriteRateLimitInterceptor writeRateLimit;
    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(OriginGuard originGuard,
                        WriteRateLimitInterceptor writeRateLimit,
                        AuthInterceptor authInterceptor) {
        this.originGuard = originGuard;
        this.writeRateLimit = writeRateLimit;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(originGuard)
                .addPathPatterns("/api/**")
                .order(OriginGuard.ORDER);

        registry.addInterceptor(writeRateLimit)
                .addPathPatterns("/api/**")
                .order(WriteRateLimitInterceptor.ORDER);

        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .order(AuthInterceptor.ORDER);
    }
}
