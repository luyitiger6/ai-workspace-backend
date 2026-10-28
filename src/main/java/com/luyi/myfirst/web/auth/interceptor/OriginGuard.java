package com.luyi.myfirst.web.auth.interceptor;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 写请求的来源校验（spec「写请求的来源校验」）。
 *
 * 令牌放在 cookie 里，浏览器会自动附带它，所以「谁发起了这个写请求」不能靠请求体自述。
 * SameSite=Lax 已经挡掉了跨站 POST，这里是第二道：它防的不是今天的浏览器，
 * 而是日后有人为了跨子域把 SameSite 改成 None —— 那种改动不报错、不掉测试，
 * 只有一道独立于 cookie 属性的校验能拦得住。
 *
 * 只拦会改状态的方法。GET 放行是 spec 明文规定的，前提是 GET 不产生状态变更，那是端点的责任。
 *
 * 校验失败抛 {@link BizException}，由 GlobalExceptionHandler 统一成 {code, message}，
 * 与认证失败走同一条出口。
 */
@Component
public class OriginGuard implements HandlerInterceptor {

    /**
     * 注册顺序：来源校验必须先于凭证与账户状态判定。
     *
     * 生产与测试都从这个常量取 order，「先于」因此是装配约束而不是一句注释。
     * 反过来（认证在前）会让跨站请求先撞 401，spec 要求的 CROSS_SITE_REJECTED 就永远观察不到。
     */
    public static final int ORDER = 0;

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final Set<String> allowedOrigins;

    public OriginGuard(AuthProperties properties) {
        this.allowedOrigins = properties.allowedOrigins().stream()
                .map(OriginGuard::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!WRITE_METHODS.contains(request.getMethod())) {
            return true;
        }
        String origin = originOf(request);
        if (origin == null || !allowedOrigins.contains(origin)) {
            throw new BizException(AuthErrorEnum.CROSS_SITE_REJECTED);
        }
        return true;
    }

    /**
     * 取请求的源：Origin 优先，缺失时回退 Referer。
     *
     * 两者皆缺返回 null，由调用方拒绝 —— 「没有来源头」不等于「本地请求」，
     * 把它当同源放行，恰好是 CSRF 最想要的默认值。
     */
    private static String originOf(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isBlank()) {
            return normalize(origin);
        }

        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null || referer.isBlank()) {
            return null;
        }
        // Referer 是完整 URL（带路径与查询），必须拆出 scheme://authority 才能和白名单比。
        // 解析失败按不可信处理：宁可误拒，也不拿一个能解析出半截的结果去比。
        try {
            URI uri = URI.create(referer.trim());
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return null;
            }
            return normalize(uri.getScheme() + "://" + uri.getRawAuthority());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 归一化后再比对：尾斜杠、大小写、两侧空白都不该决定一个请求是否可信。
     *
     * scheme 与 host 本就大小写不敏感；端口是数字，不受影响。
     * 刻意不做「补默认端口」之类的推断 —— 登记的是什么样，就算什么样。
     */
    private static String normalize(String origin) {
        String trimmed = origin.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
