package com.luyi.myfirst.common.web;

import com.luyi.myfirst.web.auth.dto.CaptchaResponse;
import com.luyi.myfirst.web.auth.dto.LoginResponse;
import com.luyi.myfirst.web.auth.dto.MeResponse;
import com.luyi.myfirst.web.auth.dto.RegisterResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.core.MethodParameter;
import org.springframework.http.server.ServerHttpRequest;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局响应包装是唯一出站通道（tasks 6.1）——守卫的判定逻辑。
 *
 * spec 的措辞是「所有响应 MUST 经统一响应包装出站，不得由各端点自行拼装响应体」，落地方式是
 * {@link AuthResponseGuard}：一个 {@code ResponseBodyAdvice}，在响应体写出前按类型判定——凡是
 * {@code /api/auth/**} 的响应体，没实现 {@link AuthResponseBody} 标记的一律拦下。
 *
 * 本类直接调 {@code beforeBodyWrite} 取证，不经 MVC 管线：这是确定性最强的一层，不依赖序列化、
 * 不依赖异常如何冒泡，逐个钉住「已登记类型放行 / 未登记类型抛异常 / 非认证路径不管 / 空体放行 /
 * 框架错误页放行」。守卫「确实被 Spring 当作 ResponseBodyAdvice 调用」这件事，由
 * {@link AuthResponseGuardWiringTest} 在真实上下文里端到端证明——两层各管一半，合起来才完整。
 *
 * 五个已登记类型全部过一遍：将来谁新增一个认证响应 DTO 却忘了打标记，第一条用例就会红。
 */
class AuthResponseGuardTest {

    private final AuthResponseGuard guard = new AuthResponseGuard();

    @Test
    @DisplayName("supports 恒为 true：放行与否交给 beforeBodyWrite 按路径与类型判定")
    void supportsEveryReturnTypeSoThePathFilterDecides() {
        assertThat(guard.supports(null, null)).isTrue();
    }

    @Test
    @DisplayName("五个已登记的认证响应类型在 /api/auth 下全部原样放行")
    void everyRegisteredAuthResponseBodyPassesThrough() {
        List<AuthResponseBody> registered = List.of(
                new CaptchaResponse("captcha-id", "data:image/png;base64,AAA"),
                new LoginResponse("public-id", "a@b.c", "A", Instant.now()),
                new RegisterResponse("public-id", "a@b.c", "A"),
                new MeResponse("public-id", "a@b.c", "A", "active", Instant.now()),
                new ErrorResponse("UNAUTHENTICATED", "请先登录"));

        for (AuthResponseBody body : registered) {
            Object out = guard.beforeBodyWrite(body, null, null, null, request("/api/auth/anything"), null);
            assertThat(out).as("%s 应原样放行", body.getClass().getSimpleName()).isSameAs(body);
        }
    }

    @Test
    @DisplayName("未登记类型写在 /api/auth 下被拦下：Map、String、自定义 record 都算")
    void unmarkedBodyOnAuthPathIsIntercepted() {
        assertThatThrownBy(() -> guard.beforeBodyWrite(
                Map.of("passwordHash", "$2a$10$leak"), null, null, null, request("/api/auth/me"), null))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> guard.beforeBodyWrite(
                "raw-string", null, null, null, request("/api/auth/login"), null))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> guard.beforeBodyWrite(
                new UnregisteredDto("x"), null, null, null, request("/api/auth/register"), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("作用域仅限 /api/auth/**：其它路径的未登记响应体原样放行（design Q16）")
    void bodiesOutsideAuthPathAreUntouched() {
        Object body = Map.of("anything", "value");
        assertThat(guard.beforeBodyWrite(body, null, null, null, request("/api/todos"), null)).isSameAs(body);
        assertThat(guard.beforeBodyWrite(body, null, null, null, request("/probe/protected"), null)).isSameAs(body);
    }

    @Test
    @DisplayName("空体放行：204/202 没有响应体，也就没有可泄漏的字段")
    void emptyBodyPassesThrough() {
        assertThat(guard.beforeBodyWrite(null, null, null, null, request("/api/auth/logout"), null)).isNull();
    }

    @Test
    @DisplayName("框架错误页放行：未捕获异常的 500 兜底体不被守卫二次拦下（8.5 依赖此豁免）")
    void frameworkErrorPageBodyIsExempt() throws Exception {
        // 未捕获异常 → 容器 ERROR 转发到 BasicErrorController，它写出的是一份属性 Map（未标记）。
        // 若守卫把这份错误页也拦下，一个 500 就会变成「写 500 时又抛异常」的二次故障。
        // 豁免的判据是处理器来源在 org.springframework 下，而不是路径——错误转发期间路径仍可能是 /api/auth/**。
        Method errorMethod = BasicErrorController.class.getDeclaredMethod("error", HttpServletRequest.class);
        MethodParameter frameworkReturnType = new MethodParameter(errorMethod, -1);
        Object errorPageBody = Map.of("status", 500, "error", "Internal Server Error");

        assertThat(guard.beforeBodyWrite(
                errorPageBody, frameworkReturnType, null, null, request("/api/auth/login"), null))
                .isSameAs(errorPageBody);
    }

    private ServerHttpRequest request(String path) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(path));
        return request;
    }

    /** 未登记标记的响应体样本：用来证明守卫按类型拦截，而不是按路径一律放行。 */
    record UnregisteredDto(String value) {
    }
}
