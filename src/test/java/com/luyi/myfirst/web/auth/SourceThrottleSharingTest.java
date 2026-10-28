package com.luyi.myfirst.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.service.auth.support.LoginThrottle;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.interceptor.SourceKeyResolver;
import com.luyi.myfirst.web.auth.interceptor.TrustedProxySourceKeyResolver;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 来源识别的四条验收断言（tasks 5.9），全部以「429 出现在哪个请求上」作证据。
 *
 * 阈值压到 3：这里要证的是「哪几条请求落进了同一个桶」，桶多大不重要，
 * 而每个请求都要真付一次 bcrypt——用生产阈值会把 4 条用例变成 80 次比对。
 *
 * 对端地址与转发头都由用例显式给：{@code 10.0.0.0/8} 在测试上下文里被登记为可信代理，
 * {@code 203.0.113.0/24} 则代表「直达的调用方」。
 *
 * 来源计数活在共享缓存里，数据库回滚救不了它；每个用例自己登记用到的键，
 * 用完精确删除，否则「未知来源」桶会污染同一 Redis 上的其他用例。
 *
 * 开跑前也清一遍：上一轮被中断时留下的计数会活到窗口结束（10 分钟），
 * 那条用例就会拿着别人的计数收场，红得毫无道理。
 */
@SpringBootTest(properties = {
        "wanderchain.auth.trusted-proxies[0]=10.0.0.0/8",
        "wanderchain.auth.login.source-max=3"
})
@AutoConfigureMockMvc
@Transactional
class SourceThrottleSharingTest {

    /** 落在已登记网段里：它的转发头才被采信。 */
    private static final String TRUSTED_PEER = "10.9.9.9";

    /** 直达的调用方：自带什么转发头都不算数。 */
    private static final String UNTRUSTED_PEER = "203.0.113.9";

    private static final String ORIGIN = "http://localhost:3000";
    /** 长度合法但凭证必然错误：每次请求都记一次来源失败。 */
    private static final String BAD_PASSWORD = "wrong-password-123";
    private static final int SOURCE_MAX = 3;

    /** 本类会用到的全部「对端 + 转发头」组合，键由生产同一套解析器算出（见 {@link #keyFor}）。 */
    private static final List<String[]> USED_SOURCES = List.of(
            new String[]{UNTRUSTED_PEER, null},
            new String[]{TRUSTED_PEER, "198.51.100.60"},
            new String[]{TRUSTED_PEER, "198.51.100.90"},
            new String[]{TRUSTED_PEER, "2001:db8:aa:bb::1"},
            new String[]{TRUSTED_PEER, "2001:db8:aa:cc::1"});

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoginThrottle throttle;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private SourceKeyResolver sourceKeys;

    private final List<String> tracked = new ArrayList<>();

    /**
     * 「未知来源」桶是跨类共享的：其他用例（以及 5.4 的 LoginSourceThrottleTest）
     * 会把真实请求计进同一个键。开工前先清空所有可能用到的桶，
     * 否则第一条用例就会拿着别人的计数收场。
     */
    @BeforeEach
    void clearSourceCountersBefore() {
        List<String> keys = new ArrayList<>();
        keys.add(throttle.sourceKeyOf(TrustedProxySourceKeyResolver.UNKNOWN_SOURCE));
        for (String[] source : USED_SOURCES) {
            keys.add(keyFor(source[0], source[1]));
        }
        redis.delete(keys);
        tracked.clear();
    }

    @AfterEach
    void clearSourceCountersAfter() {
        List<String> keys = new ArrayList<>(tracked);
        keys.add(throttle.sourceKeyOf(TrustedProxySourceKeyResolver.UNKNOWN_SOURCE));
        redis.delete(keys);
        tracked.clear();
    }

    @Test
    @DisplayName("伪造转发头不扩容：每条都声称一个新地址，仍然撞在同一额度上")
    void forgedForwardedHeaderDoesNotExpandQuota() throws Exception {
        track(UNTRUSTED_PEER, null);

        for (int i = 1; i <= SOURCE_MAX; i++) {
            login(UNTRUSTED_PEER, "198.51.100." + i, "ghost-" + i + "@example.com")
                    .andExpect(status().isUnauthorized());
        }

        // 声称的地址是全新的：只要任何一个被采信，这次就是一个空桶，不会 429。
        login(UNTRUSTED_PEER, "198.51.100.200", "ghost@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("未知来源不被放行，也不与已解析来源合并：它满了，真实来源仍有自己的额度")
    void unknownSourceIsThrottledAndKeptSeparate() throws Exception {
        track(UNTRUSTED_PEER, null);
        track(TRUSTED_PEER, "198.51.100.60");

        for (int i = 0; i < SOURCE_MAX; i++) {
            login(UNTRUSTED_PEER, null, "ghost-" + i + "@example.com")
                    .andExpect(status().isUnauthorized());
        }

        // 未知桶已满：这次还带了转发头，但换成新桶还是拦下——既没被放行，也没被扩容。
        login(UNTRUSTED_PEER, "198.51.100.61", "ghost@example.com")
                .andExpect(status().isTooManyRequests());

        // 可解析的来源额度是独立的：未知桶满不会连坐真实来源。
        login(TRUSTED_PEER, "198.51.100.60", "ghost@example.com")
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("IPv6 同 /64 共用额度：换个地址不换来新额度，换个前缀才有")
    void ipv6AddressesInOneSlash64ShareQuota() throws Exception {
        track(TRUSTED_PEER, "2001:db8:aa:bb::1");
        track(TRUSTED_PEER, "2001:db8:aa:cc::1");

        login(TRUSTED_PEER, "2001:db8:aa:bb::1", "ghost-1@example.com")
                .andExpect(status().isUnauthorized());
        login(TRUSTED_PEER, "2001:db8:aa:bb::2", "ghost-2@example.com")
                .andExpect(status().isUnauthorized());
        login(TRUSTED_PEER, "2001:db8:aa:bb:1:2:3:4", "ghost-3@example.com")
                .andExpect(status().isUnauthorized());

        // 同前缀的第 4 个地址：共用计数已满。
        login(TRUSTED_PEER, "2001:db8:aa:bb::dead:beef", "ghost@example.com")
                .andExpect(status().isTooManyRequests());

        // 换一个 /64 才拿到独立额度。
        login(TRUSTED_PEER, "2001:db8:aa:cc::1", "ghost@example.com")
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("单来源撞多账户共用额度：每个账户只错一次也拦得住")
    void oneSourceTryingManyAccountsSharesQuota() throws Exception {
        track(TRUSTED_PEER, "198.51.100.90");

        for (int i = 0; i < SOURCE_MAX; i++) {
            login(TRUSTED_PEER, "198.51.100.90", "someone-" + i + "@example.com")
                    .andExpect(status().isUnauthorized());
        }

        // 换第 4 个账户也一样：来源维度的计数不区分账户，这正是撞库的样子。
        login(TRUSTED_PEER, "198.51.100.90", "yet-another@example.com")
                .andExpect(status().isTooManyRequests());
    }

    private ResultActions login(String peer, String forwardedFor, String email) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .with(request -> {
                    request.setRemoteAddr(peer);
                    return request;
                })
                .content(objectMapper.writeValueAsString(
                        new LoginRequest(email, BAD_PASSWORD, null, null)));
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return mockMvc.perform(builder);
    }

    /**
     * 先按生产同一套规则算出这次会落在哪个桶，登记下来以便收尾精确删除。
     * 存的是**加过前缀**的限流键：只存原始来源串，删除就成了空操作，
     * 计数会一直活到窗口过期——这条缺陷正是本类曾把状态漏给下一轮用例的原因。
     */
    private void track(String peer, String forwardedFor) {
        tracked.add(keyFor(peer, forwardedFor));
    }

    private String keyFor(String peer, String forwardedFor) {
        MockHttpServletRequest probe = new MockHttpServletRequest();
        probe.setRemoteAddr(peer);
        if (forwardedFor != null) {
            probe.addHeader("X-Forwarded-For", forwardedFor);
        }
        return throttle.sourceKeyOf(sourceKeys.resolve(probe));
    }
}
