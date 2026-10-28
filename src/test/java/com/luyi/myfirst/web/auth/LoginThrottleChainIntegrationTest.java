package com.luyi.myfirst.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.support.LoginThrottle;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.interceptor.SourceKeyResolver;
import com.luyi.myfirst.web.auth.interceptor.TrustedProxySourceKeyResolver;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 限流全链路集成验收（tasks 8.2）：连续失败达阈值 → 要求图形码 → 来源超阈 429 → 正确凭证仍可登录。
 *
 * 与既有限流测试的分工：{@code LoginThrottleTest} 直接 new 出 {@code LoginThrottle} 只验计数机制，
 * {@code SourceThrottleSharingTest} 只验来源识别的四条断言（谁和谁落进同一个桶），
 * {@code LoginTest} 验单端点的三态判定——没有一个把「账户维度撞阈值 → 图形码闸门 → 来源维度撞上限 → 429
 * → 换个来源用正确凭证照样进得来」串成一条真实的因果链。本类补的正是这条链，且逐步断言账户状态全程 active。
 *
 * 阈值压到 2 / 3（{@code fail-threshold=2}、{@code source-max=3}）：要证的是「哪一步触发了哪道闸门」，
 * 阈值多大不重要，而每次失败都要真付一次 bcrypt——用生产阈值（5 / 20）会把一条链变成二十几次比对。
 *
 * 两道闸门各自的「不进行密码比对」都有可观察证据：图形码闸门触发时返回的是 {@code 400 CAPTCHA_INVALID}
 * 而不是 {@code 401 UNAUTHENTICATED}（真比对过密码就会是后者）；来源闸门触发时即便带**正确密码**也返回
 * {@code 429}（真比对过密码就会是 200）——错误码与状态码本身就是「短路发生在密码之前」的证据。
 *
 * 最后一步「正确凭证仍可登录」刻意换一个来源：来源 A 已被 429 挡住且在窗口内不会自己解开，
 * 若仍从 A 登录就只能靠删键模拟窗口过期。换来源 B 才是这条链要证的性质——限流是**来源**维度的，
 * 账户本身从未被锁；spec「限流不改变账户状态」明写「仍能以正确凭证登录成功（可能需先通过图形码）」，
 * 故 B 仍带一张有效图形码（账户维度计数此时仍高于阈值）。
 *
 * 来源计数活在共享缓存里，数据库回滚救不了它；本类用生产同一套解析器算出实际键，开跑前与收尾各清一遍，
 * 否则「未知来源」桶与被中断轮次的残留会污染同一 Redis 上的其他用例（见 {@link #keyFor}）。
 */
@SpringBootTest(properties = {
        "wanderchain.auth.trusted-proxies[0]=10.0.0.0/8",
        "wanderchain.auth.login.fail-threshold=2",
        "wanderchain.auth.login.source-max=3"
})
@AutoConfigureMockMvc
@Transactional
class LoginThrottleChainIntegrationTest {

    /** 落在已登记网段里：它的转发头才被采信，两个不同 XFF 因此解析成两个独立来源。 */
    private static final String TRUSTED_PEER = "10.9.9.9";
    private static final String SOURCE_A_XFF = "198.51.100.10";
    private static final String SOURCE_B_XFF = "198.51.100.20";

    private static final String EMAIL = "throttle-chain@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String WRONG_PASSWORD = "wrong-password-123";
    private static final String DISPLAY_NAME = "Throttle Subject";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String CAPTCHA_ANSWER = "ABCD";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";

    /** 与类级 properties 里的阈值同源，仅用于让循环次数读起来是「阈值」而不是魔数。 */
    private static final int FAIL_THRESHOLD = 2;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordHasher hasher;
    @Autowired private CaptchaServiceImpl captcha;
    @Autowired private LoginThrottle throttle;
    @Autowired private SourceKeyResolver sourceKeys;
    @Autowired private StringRedisTemplate redis;

    private final List<String> keys = new ArrayList<>();
    private Long accountId;

    @BeforeEach
    void clearCountersBefore() {
        keys.add(throttle.sourceKeyOf(TrustedProxySourceKeyResolver.UNKNOWN_SOURCE));
        keys.add(keyFor(SOURCE_A_XFF));
        keys.add(keyFor(SOURCE_B_XFF));
        redis.delete(keys);
        keys.clear();
    }

    @AfterEach
    void clearCountersAfter() {
        if (accountId != null) {
            throttle.clearAccountFailures(accountId);
        }
        keys.add(throttle.sourceKeyOf(TrustedProxySourceKeyResolver.UNKNOWN_SOURCE));
        keys.add(keyFor(SOURCE_A_XFF));
        keys.add(keyFor(SOURCE_B_XFF));
        redis.delete(keys);
        keys.clear();
    }

    @Test
    @DisplayName("限流全链路：撞账户阈值→要图形码→撞来源上限 429→换来源正确凭证仍登录，账户全程 active")
    void throttleChainGatesThenBlocksSourceButNeverLocksTheAccount() throws Exception {
        Account account = accounts.saveAndFlush(
                Account.createActive(Ulid.generate(), EMAIL, hasher.hash(PASSWORD), DISPLAY_NAME));
        accountId = account.id();
        // 账户维度计数活在共享缓存里、TTL 长达 30 天，键取自 accounts 的自增 id。测试库每次
        // @Transactional 回滚后表是空的、自增值却停在低位，于是本用例新种的账户会抽到一个
        // 「别的物理账户上一轮留下过失败计数」的 id——requiresCaptcha 因此在里程碑 1 之前就已为真，
        // 401 被顶成 400 CAPTCHA_INVALID。@BeforeEach 只清来源键（那时尚未种账户），账户维度只能
        // 在种下之后清：一个新账户本就不该有任何连续失败历史，这里清掉的是陈旧残留，不是本用例的状态。
        throttle.clearAccountFailures(accountId);
        assertAccountStillActive("建账户后");

        // ── 里程碑 1：来源 A 连续失败达账户阈值（fail-threshold=2），每次都真比对密码后 401。
        for (int i = 0; i < FAIL_THRESHOLD; i++) {
            loginFrom(SOURCE_A_XFF, EMAIL, WRONG_PASSWORD, null, null)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        assertThat(throttle.requiresCaptcha(accountId))
                .as("连续失败达阈值后账户维度应开始要求图形码").isTrue();
        assertAccountStillActive("撞账户阈值后");

        // ── 里程碑 2：要求图形码。不带图形码再来一次，闸门在密码比对之前短路 → 400 CAPTCHA_INVALID。
        loginFrom(SOURCE_A_XFF, EMAIL, WRONG_PASSWORD, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_INVALID"));
        assertAccountStillActive("触发图形码闸门后");

        // ── 里程碑 3：带有效图形码但密码仍错，把来源 A 的额度用满（source-max=3）。
        //    此时来源计数为 2（前两次失败），本次经图形码闸门后落到密码比对再记一次 → 3，来源 A 被挡。
        CaptchaIssue toBurn = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + toBurn.captchaId());
        loginFrom(SOURCE_A_XFF, EMAIL, WRONG_PASSWORD, toBurn.captchaId(), CAPTCHA_ANSWER)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(throttle.isSourceBlocked(sourceA()))
                .as("来源 A 失败达上限后应被挡").isTrue();
        assertAccountStillActive("撞来源上限后");

        // ── 里程碑 4：来源超阈 429。刻意带**正确密码**——仍 429 且携带 Retry-After，
        //    正是「来源闸门在密码比对之前短路、对正确凭证同样生效」的证据。
        loginFrom(SOURCE_A_XFF, EMAIL, PASSWORD, null, null)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After",
                        String.valueOf(throttle.sourceRetryAfter().toSeconds())));
        assertAccountStillActive("来源被 429 挡住后");

        // ── 里程碑 5：换一个未被挡的来源 B，用正确凭证（账户维度仍要求图形码，故带一张有效码）登录成功。
        //    这一步是整条链的落点：账户从未被锁，429 只封了来源 A，合法用户从别处照样进得来。
        CaptchaIssue toPass = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + toPass.captchaId());
        MvcResult success = loginFrom(SOURCE_B_XFF, EMAIL, PASSWORD, toPass.captchaId(), CAPTCHA_ANSWER)
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();
        Cookie cookie = success.getResponse().getCookie(AuthCookie.NAME);
        assertThat(cookie).as("换来源后用正确凭证应登录成功并下发 cookie").isNotNull();
        assertAccountStillActive("正确凭证登录成功后");

        // 成功登录清零账户维度计数（spec「成功登录后放宽要求」）：下一次登录不再要求图形码。
        assertThat(throttle.requiresCaptcha(accountId))
                .as("一次成功登录应清零账户维度连续失败计数").isFalse();
    }

    private void assertAccountStillActive(String milestone) {
        assertThat(accounts.findById(accountId).orElseThrow().status())
                .as("限流全程账户状态不变（%s）", milestone)
                .isEqualTo(AccountStatus.ACTIVE);
    }

    private ResultActions loginFrom(String forwardedFor, String email, String password,
                                    String captchaId, String captchaAnswer) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header("X-Forwarded-For", forwardedFor)
                .with(request -> {
                    request.setRemoteAddr(TRUSTED_PEER);
                    return request;
                })
                .content(objectMapper.writeValueAsString(
                        new LoginRequest(email, password, captchaId, captchaAnswer)));
        return mockMvc.perform(builder);
    }

    /** 用生产同一套解析器算出来源 A 的实际来源串（未加前缀），供 {@code isSourceBlocked} 判定。 */
    private String sourceA() {
        MockHttpServletRequest probe = new MockHttpServletRequest();
        probe.setRemoteAddr(TRUSTED_PEER);
        probe.addHeader("X-Forwarded-For", SOURCE_A_XFF);
        return sourceKeys.resolve(probe);
    }

    /**
     * 先按生产同一套规则算出这次会落在哪个桶，返回**加过前缀**的限流键以便精确删除。
     * 只存原始来源串，删除就成了空操作，计数会一直活到窗口过期，把状态漏给下一轮用例。
     */
    private String keyFor(String forwardedFor) {
        MockHttpServletRequest probe = new MockHttpServletRequest();
        probe.setRemoteAddr(TRUSTED_PEER);
        probe.addHeader("X-Forwarded-For", forwardedFor);
        return throttle.sourceKeyOf(sourceKeys.resolve(probe));
    }
}
