package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;
import com.luyi.myfirst.service.auth.support.VerificationCodeSender;

import com.luyi.myfirst.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模拟发信实现的两条护栏（tasks 4.8，D8）：只在非生产 profile 生效，且没有任何端点能读出明文码。
 *
 * 第二条用「端点清单」而不是「逐个检查响应体」：清单是白名单，将来有人加一个
 * `/api/auth/debug/code` 之类的端点，这里立刻红。逐个检查响应体只能覆盖被写进测试的那几个。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SimulatedSenderGuardTest {

    private static final String EMAIL = "mallory@example.com";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CaptchaServiceImpl captcha;

    @Autowired
    private RegisterCodeServiceImpl registerCodes;

    @Autowired
    private OutboxVerificationCodeSender outbox;

    @Autowired
    private EmailQuotaGuard quota;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void resetState() {
        outbox.clear();
        String normalized = EmailNormalizer.normalize(EMAIL);
        redis.delete(List.of(
                registerCodes.keyOf(normalized),
                registerCodes.failKeyOf(normalized),
                registerCodes.cooldownKeyOf(normalized),
                quota.keyOf(normalized)));
    }

    @AfterEach
    void cleanUp() {
        if (!keys.isEmpty()) {
            redis.delete(keys);
            keys.clear();
        }
    }

    @Test
    @DisplayName("prod profile 下没有真实发信实现：启动直接失败，不会带病上线")
    void productionProfileRefusesToStartWithoutRealSender() {
        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(Application.class)
                    .profiles("prod")
                    .web(WebApplicationType.NONE)
                    .run()) {
                // 走到这里说明启动成功了 —— 那正是这条用例要拦住的情况。
            }
        })
                .isInstanceOf(Exception.class)
                .hasStackTraceContaining("VerificationCodeSender");
    }

    @Test
    @DisplayName("端点清单：/api/auth 下只有登记过的三个端点，没有读验证码的接口")
    void authEndpointsAreExactlyTheRegisteredOnes() {
        // 新增端点必须显式加到这里：让「悄悄多出一个能读码的接口」变成一次红灯，
        // 而不是上线后在日志里发现。
        assertThat(authEndpoints()).containsExactlyInAnyOrder(
                "GET /api/auth/captcha",
                "POST /api/auth/register",
                "POST /api/auth/register-code");
    }

    @Test
    @DisplayName("换发注册码的响应体为空：码只走投递通道")
    void registerCodeResponseHasEmptyBody() throws Exception {
        CaptchaIssue issue = captchaOf("ABCD");
        String body = "{\"email\":\"" + EMAIL + "\",\"captchaId\":\"" + issue.captchaId()
                + "\",\"captchaAnswer\":\"ABCD\"}";

        String response = mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register-code")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(outbox.messages()).hasSize(1);
        assertThat(response).doesNotContain(outbox.messages().get(0).code());
    }

    @Test
    @DisplayName("图形码响应里不含已发出的注册码")
    void captchaResponseDoesNotContainIssuedCode() throws Exception {
        CaptchaIssue issue = captchaOf("EFGH");
        registerCodes.issue(EMAIL, issue.captchaId(), "EFGH");
        String code = outbox.messages().get(0).code();

        String response = mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/captcha"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(response).doesNotContain(code);
    }

    /** `/api/auth` 下的全部映射，形如 `GET /api/auth/captcha`。 */
    private Set<String> authEndpoints() {
        Set<String> endpoints = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/auth")) {
                    continue;
                }
                if (methods.isEmpty()) {
                    endpoints.add("ANY " + pattern);
                    continue;
                }
                for (RequestMethod method : methods) {
                    endpoints.add(method.name() + " " + pattern);
                }
            }
        });
        return endpoints;
    }

    private CaptchaIssue captchaOf(String answer) {
        CaptchaIssue issue = captcha.issue(answer);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());
        return issue;
    }
}
