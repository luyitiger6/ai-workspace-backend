package com.luyi.myfirst.service.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写请求来源校验的四条契约（spec「写请求的来源校验」）。
 *
 * 两个拦截器都注册在探针路径上，order 取自它们各自的常量——与生产装配同一个来源，
 * 这样「来源校验先于凭证判定」验的是真实顺序，而不是测试自己排的队。
 *
 * 探针控制器记录被调用次数：403 的断言不能只看状态码，
 * 还要证明业务方法根本没被进入，否则「先拒后执行」和「先执行后拒」长得一模一样。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OriginGuardTest.ProbeConfig.class)
class OriginGuardTest {

    private static final String REGISTERED_ORIGIN = "http://localhost:3000";

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetCounter() {
        ProbeController.WRITES.set(0);
    }

    @Test
    @DisplayName("跨站写请求：403 与 CROSS_SITE_REJECTED，且业务方法一次都没被调用")
    void rejectsCrossSiteWrite() throws Exception {
        mockMvc.perform(post("/probe/write").header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));

        assertThat(ProbeController.WRITES).hasValue(0);
    }

    @Test
    @DisplayName("已登记的来源：正常执行，不受来源校验干扰")
    void allowsRegisteredOrigin() throws Exception {
        mockMvc.perform(post("/probe/write").header(HttpHeaders.ORIGIN, REGISTERED_ORIGIN))
                .andExpect(status().isNoContent());

        assertThat(ProbeController.WRITES).hasValue(1);
    }

    @Test
    @DisplayName("同一个站点的另一种写法（127.0.0.1）也已登记：不会把自己人拦在外面")
    void allowsLoopbackVariant() throws Exception {
        mockMvc.perform(post("/probe/write").header(HttpHeaders.ORIGIN, "http://127.0.0.1:3000"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("未登记的子域：拒绝——这正是「显式登记」要的效果")
    void rejectsUnregisteredSubdomain() throws Exception {
        mockMvc.perform(post("/probe/write").header(HttpHeaders.ORIGIN, "http://app.localhost:3000"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));
    }

    @Test
    @DisplayName("没有 Origin 但有 Referer：用 Referer 的源参与校验，路径与查询不参与比对")
    void fallsBackToReferer() throws Exception {
        mockMvc.perform(post("/probe/write")
                        .header(HttpHeaders.REFERER, REGISTERED_ORIGIN + "/login?next=/community"))
                .andExpect(status().isNoContent());

        assertThat(ProbeController.WRITES).hasValue(1);
    }

    @Test
    @DisplayName("Referer 指向别的站点：拒绝")
    void rejectsCrossSiteReferer() throws Exception {
        mockMvc.perform(post("/probe/write").header(HttpHeaders.REFERER, "https://evil.example/attack.html"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));
    }

    @Test
    @DisplayName("Referer 解析不出源：拒绝，不拿半截结果去比")
    void rejectsUnparseableReferer() throws Exception {
        mockMvc.perform(post("/probe/write").header(HttpHeaders.REFERER, "not a url"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));
    }

    @Test
    @DisplayName("Origin 与 Referer 皆缺失：拒绝——缺省不等于同源")
    void rejectsWhenBothHeadersMissing() throws Exception {
        mockMvc.perform(post("/probe/write"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));

        assertThat(ProbeController.WRITES).hasValue(0);
    }

    @Test
    @DisplayName("Origin 为 null（沙箱 iframe 与部分重定向）：拒绝")
    void rejectsSandboxedNullOrigin() throws Exception {
        mockMvc.perform(post("/probe/write").header(HttpHeaders.ORIGIN, "null"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));
    }

    @Test
    @DisplayName("跨站且凭证无效：CROSS_SITE_REJECTED 而不是 UNAUTHENTICATED，凭证根本没被比对")
    void rejectsCrossSiteBeforeCredentials() throws Exception {
        mockMvc.perform(post("/probe/protected-write")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .cookie(new Cookie(AuthCookie.NAME, "not-a-real-token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));

        assertThat(ProbeController.WRITES).hasValue(0);
    }

    @Test
    @DisplayName("对照组：同源且凭证无效才轮到 401——两个拦截器确实按顺序都在工作")
    void checksCredentialsAfterOriginPasses() throws Exception {
        mockMvc.perform(post("/probe/protected-write")
                        .header(HttpHeaders.ORIGIN, REGISTERED_ORIGIN)
                        .cookie(new Cookie(AuthCookie.NAME, "not-a-real-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(ProbeController.WRITES).hasValue(0);
    }

    @Test
    @DisplayName("PUT、PATCH、DELETE 与 POST 同等对待：凡是会改状态的方法都要校验")
    void guardsEveryWriteMethod() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                put("/probe/write"), patch("/probe/write"), delete("/probe/write"))) {
            mockMvc.perform(request.header(HttpHeaders.ORIGIN, "https://evil.example"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));
        }

        assertThat(ProbeController.WRITES).hasValue(0);
    }

    @Test
    @DisplayName("GET 不做来源校验：spec 明确豁免，前提是 GET 不产生状态变更")
    void skipsReadRequests() throws Exception {
        mockMvc.perform(get("/probe/read").header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("归一化：大小写与尾斜杠不决定一个请求是否可信")
    void normalizesBeforeComparing() throws Exception {
        mockMvc.perform(post("/probe/write").header(HttpHeaders.ORIGIN, "HTTP://LOCALHOST:3000/"))
                .andExpect(status().isNoContent());
    }

    @TestConfiguration
    static class ProbeConfig implements WebMvcConfigurer {

        private final OriginGuard originGuard;
        private final AuthInterceptor authInterceptor;

        ProbeConfig(OriginGuard originGuard, AuthInterceptor authInterceptor) {
            this.originGuard = originGuard;
            this.authInterceptor = authInterceptor;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(originGuard)
                    .addPathPatterns("/probe/**")
                    .order(OriginGuard.ORDER);
            registry.addInterceptor(authInterceptor)
                    .addPathPatterns("/probe/protected-write")
                    .order(AuthInterceptor.ORDER);
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        /** 业务方法被进入的次数：403 之后必须仍为 0。 */
        static final AtomicInteger WRITES = new AtomicInteger();

        @RequestMapping(value = "/probe/write",
                method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
        ResponseEntity<Void> write() {
            WRITES.incrementAndGet();
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/probe/read")
        ResponseEntity<Void> read() {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/probe/protected-write")
        ResponseEntity<Void> protectedWrite() {
            WRITES.incrementAndGet();
            return ResponseEntity.noContent().build();
        }
    }
}
