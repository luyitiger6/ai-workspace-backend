package com.luyi.myfirst.web.auth.interceptor;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.auth.support.TokenVerifier;
import com.luyi.myfirst.web.auth.AuthCookie;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 认证拦截器：{@code /api/**} 全覆盖，公开路由表之外一律默认拒绝（design D3）。
 *
 * 只从 cookie 读令牌，不读 Authorization 头（D8）：令牌一旦允许两条入口，
 * 「响应体不含令牌」和「cookie 属性齐备」这两条契约就只覆盖了一半。
 *
 * 判定顺序（顺序本身就是契约）：
 * <ol>
 *   <li>命中公开路由表（路径匹配 **且** 方法在集合里）→ <b>尽力</b>解析令牌，失败静默，放行；</li>
 *   <li>否则解析令牌，失败抛 {@link BizException}（401）。</li>
 * </ol>
 *
 * 为什么公开分支也要解析令牌：{@code GET /api/posts/{id}} 要满足「作者能读自己的草稿，
 * 别人得到 404」。公开分支若直接 {@code return true}，控制器拿不到身份，
 * 草稿对作者本人也不可见。所以这里静默写入 attribute，失败不抛。
 *
 * 为什么是「默认拒绝」而不是「排除清单」：{@code addPathPatterns}/{@code excludePathPatterns}
 * 表达不了「同一路径 GET 公开、POST 受保护」。默认拒绝让「新端点忘记登记」的结果是 401
 * ——显性、测试立刻发现——而不是悄悄公开可写。
 *
 * 校验失败抛 {@link BizException} 而不是自己拼 JSON——错误体由 GlobalExceptionHandler
 * 统一产出，拦截器与控制器因此共享同一套 {code, message} 契约。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 请求属性名：控制器从这里取「账户 + jti」，不重复解析令牌。 */
    public static final String VERIFIED_ATTRIBUTE = "wanderchain.auth.verified";

    /**
     * 注册顺序：必须晚于 {@link OriginGuard#ORDER}。
     *
     * 跨站请求先被来源校验拦下，就不会走到这里 —— 否则它会先得到一个 401，
     * 让「来源不可信」和「没登录」在调用方那里变成同一件事。
     */
    public static final int ORDER = 10;

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /**
     * 公开路由表：路径模式 → 允许的方法集合。
     *
     * 用 {@link LinkedHashMap} 保序只为让「读起来与本注释的表格一致」；
     * 判定是「任一命中」语义，与顺序无关。
     */
    private static final Map<String, Set<String>> PUBLIC_ROUTES = publicRoutes();

    private final TokenVerifier verifier;

    public AuthInterceptor(TokenVerifier verifier) {
        this.verifier = verifier;
    }

    private static Map<String, Set<String>> publicRoutes() {
        Map<String, Set<String>> routes = new LinkedHashMap<>();
        // 认证域的 6 个公开端点：注册、登录、找回密码与图形码。
        routes.put("/api/auth/captcha", Set.of("GET"));
        routes.put("/api/auth/register-code", Set.of("POST"));
        routes.put("/api/auth/register", Set.of("POST"));
        routes.put("/api/auth/password-reset-code", Set.of("POST"));
        routes.put("/api/auth/password-reset", Set.of("POST"));
        routes.put("/api/auth/login", Set.of("POST"));
        // 帖子域只公开读：列表、详情、评论列表。
        routes.put("/api/posts", Set.of("GET"));
        routes.put("/api/posts/*", Set.of("GET"));
        routes.put("/api/posts/*/comments", Set.of("GET"));
        return Map.copyOf(routes);
    }

    /**
     * 该「路径 + 方法」组合是否属于公开访问。
     *
     * 单独暴露成方法而不是埋进 {@link #preHandle}：design D4 的九宫格要能逐行被测试钉住，
     * 否则「哪些端点公开」又变成靠读代码维护的事实。
     */
    public static boolean isPublicRoute(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, Set<String>> route : PUBLIC_ROUTES.entrySet()) {
            if (!route.getValue().contains(normalizedMethod)) {
                continue;
            }
            if (MATCHER.match(route.getKey(), path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = readToken(request);
        VerifiedToken verified = token == null ? null : verifier.verify(token).orElse(null);

        if (isPublicRoute(request.getMethod(), requestPath(request))) {
            // 尽力解析：成功则写入身份（作者可读自己的草稿），失败静默放行。
            if (verified != null) {
                request.setAttribute(VERIFIED_ATTRIBUTE, verified);
            }
            return true;
        }

        if (verified == null) {
            throw new BizException(AuthErrorEnum.UNAUTHENTICATED);
        }
        request.setAttribute(VERIFIED_ATTRIBUTE, verified);
        return true;
    }

    /** 去掉上下文路径后的请求路径，路由表按它比对。 */
    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private static String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthCookie.NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
