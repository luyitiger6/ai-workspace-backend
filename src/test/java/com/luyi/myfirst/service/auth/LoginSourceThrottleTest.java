package com.luyi.myfirst.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.config.AuthProperties;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.support.LoginThrottle;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.interceptor.TrustedProxySourceKeyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 来源维度限流的接线（tasks 5.4）：429 必须发生在密码比对之前。
 *
 * 这条契约不是读代码读出来的，靠两个方向夹逼：
 * 正确密码也拿不到令牌——来源闸门在成功路径之前；
 * 错误密码不留下任何账户维度的失败痕迹——密码比对压根没发生。
 *
 * 本类故意用真实来源策略：MockMvc 的对端地址不在 trusted-proxies 里，请求因此按
 * 「未知来源」计数——这也是 application.yml 把 trusted-proxies 留空后生产上的实际行为。
 * 用例自己把额度灌满，留在桶里由窗口 TTL 收回：它只影响同样走真实来源的用例，
 * 而其他登录用例都换成了各自的随机来源键。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginSourceThrottleTest {

    private static final String EMAIL = "erin@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String WRONG_PASSWORD = "correct-horse-battery-x";
    private static final String ORIGIN = "http://localhost:3000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PasswordHasher hasher;

    @Autowired
    private LoginThrottle throttle;

    @Autowired
    private AuthProperties properties;

    @Test
    @DisplayName("来源超阈直接 429：正确密码也拿不到令牌，错误密码不留失败痕迹")
    void sourceThrottlePreemptsPasswordComparison() throws Exception {
        Account account = saveAccount();
        for (int i = 0; i < properties.login().sourceMax(); i++) {
            throttle.recordSourceFailure(TrustedProxySourceKeyResolver.UNKNOWN_SOURCE);
        }

        MvcResult withCorrectPassword = login(PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After",
                        String.valueOf(properties.login().retryAfter().toSeconds())))
                .andReturn();
        assertThat(withCorrectPassword.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();

        login(WRONG_PASSWORD).andExpect(status().isTooManyRequests());

        // 账户维度的计数仍是 0：错误的密码从未被比对过，也就没有什么可记为失败的。
        assertThat(throttle.requiresCaptcha(account.id())).isFalse();
        // 账户状态不因限流改变——spec 要求失败只驱动限流，不改变账户。
        assertThat(accounts.findByEmailAndDeletedAtIsNull(EMAIL).orElseThrow().status())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    private ResultActions login(String password) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .content(objectMapper.writeValueAsString(new LoginRequest(EMAIL, password, null, null))));
    }

    private Account saveAccount() {
        return accounts.saveAndFlush(
                Account.createActive(Ulid.generate(), EMAIL, hasher.hash(PASSWORD), "Erin"));
    }
}
