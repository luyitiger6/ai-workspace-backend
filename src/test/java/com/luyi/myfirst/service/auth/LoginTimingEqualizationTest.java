package com.luyi.myfirst.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.support.LoginThrottle;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.interceptor.SourceKeyResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录的计时等价（tasks 5.5）：账户不存在与密码不对必须付同样多次的哈希比对。
 *
 * 用 spy 数次数而不是计时：bcrypt 的耗时在 CI 机器上会被 GC、容器冷启动与并行测试摊平，
 * 阈值要么松到拦不住任何东西，要么紧到随机失败；而「比对了几次、目标是不是真的 bcrypt 串」
 * 是确定的，也正好是计时等价成立的原因。
 *
 * 两个用例互为对照：只断言「不存在的那条也比对一次」的话，一个「所有路径都比对两次」的实现
 * 同样能通过，等价与否看不出来。
 *
 * 来源键换成每个用例一个随机值：本类会制造真实失败，若走真实的「未知来源」桶，
 * 会与 LoginSourceThrottleTest 灌满的额度撞车（同一个 Redis，10 分钟窗口）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginTimingEqualizationTest {

    private static final String EMAIL = "timing@example.com";
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
    private LoginThrottle throttle;

    /** 真实实现外面套一层记录器：既保留真实的 bcrypt 耗时，又能数调用次数。 */
    @MockitoSpyBean
    private PasswordHasher hasher;

    @MockitoBean
    private SourceKeyResolver sourceKey;

    /** 账户计数存在共享缓存里（30 天 TTL），数据库回滚救不了它。 */
    private Long accountIdToClean;

    @BeforeEach
    void isolateSourceAndRecorder() {
        when(sourceKey.resolve(any())).thenReturn("login-timing-" + UUID.randomUUID());
        // 同一个测试类的各用例共享同一个 spy 与 Spring 上下文，不清就会读到上一个用例的调用。
        Mockito.clearInvocations(hasher);
    }

    @AfterEach
    void cleanAccountCounter() {
        if (accountIdToClean != null) {
            throttle.clearAccountFailures(accountIdToClean);
        }
    }

    @Test
    @DisplayName("邮箱不存在也付一次 bcrypt：比对目标是真实哈希，不是 null 的快速返回")
    void unknownEmailStillPaysOneHashComparison() throws Exception {
        login("nobody-" + UUID.randomUUID() + "@example.com", PASSWORD)
                .andExpect(status().isUnauthorized());

        ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
        verify(hasher, times(1)).matches(anyString(), target.capture());

        assertThat(target.getValue())
                .as("比对目标是 null 或空串时 matches 会快速返回 false，耗时差比不做等价更明显")
                .isNotBlank()
                .startsWith("$2");
        assertThat(costOf(target.getValue()))
                .as("比对目标必须与真实账户同强度，否则次数对、耗时不同")
                .isEqualTo(costOf(hasher.hash(PASSWORD)));
    }

    @Test
    @DisplayName("账户存在且密码错误也只比对一次：等价靠「都付一次」，不是给不存在的那条补第二次")
    void existingAccountPaysExactlyOneComparison() throws Exception {
        Account account = saveAccount();

        login(EMAIL, WRONG_PASSWORD).andExpect(status().isUnauthorized());

        verify(hasher, times(1)).matches(anyString(), anyString());
        assertThat(account.status())
                .as("凭证失败不得改变账户状态")
                .isEqualTo(AccountStatus.ACTIVE);
    }

    /** bcrypt 串形如 {@code $2a$10$...}，第 5–6 位是 cost。 */
    private static String costOf(String bcryptHash) {
        return bcryptHash.substring(4, 6);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .content(objectMapper.writeValueAsString(
                        new LoginRequest(email, password, null, null))));
    }

    private Account saveAccount() {
        Account saved = accounts.saveAndFlush(
                Account.createActive(Ulid.generate(), EMAIL, hasher.hash(PASSWORD), "Timing"));
        accountIdToClean = saved.id();
        return saved;
    }
}
