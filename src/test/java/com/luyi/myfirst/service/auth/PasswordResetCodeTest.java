package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.PasswordResetCodeServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.support.CodePurpose;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.web.auth.dto.PasswordResetCodeRequest;
import com.luyi.myfirst.web.auth.dto.RegisterCodeRequest;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重置码换发的契约（tasks 7.1）。
 *
 * 发码这一半与注册码同源，所以本类关心的不是「码怎么生成」（那已被 RegisterCodeTest 钉住），
 * 而是它与注册码不同的那件事：**只发给有 active 账户的邮箱**，且这个差别不对外可观察。
 *
 * 对照组同样占一半——只断言「未注册邮箱不投递」的话，一个把投递整段删掉的实现也能通过。
 * 所以每个「不投递」的用例旁边都有一条「该投递的确实投递了」。
 *
 * 逐字段相等按响应体与状态码比对：这个端点的响应体是空的，任何差异都只能出现在
 * 状态码、头部或体内容上，三者合起来就是调用方能观察到的全部。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasswordResetCodeTest {

    private static final String REGISTERED = "ivy@example.com";
    private static final String UNREGISTERED = "jake@example.com";
    private static final String DELETED = "kate@example.com";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String CAPTCHA_ANSWER = "ABCD";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";
    private static final long TTL_SECONDS = 300L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordResetCodeServiceImpl resetCodes;

    @Autowired
    private RegisterCodeServiceImpl registerCodes;

    @Autowired
    private CaptchaServiceImpl captcha;

    @Autowired
    private OutboxVerificationCodeSender outbox;

    @Autowired
    private EmailQuotaGuard quota;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private AccountRepository accounts;

    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void resetState() {
        outbox.clear();
        resetCodeState(REGISTERED);
        resetCodeState(UNREGISTERED);
        resetCodeState(DELETED);
    }

    @AfterEach
    void cleanUp() {
        if (!keys.isEmpty()) {
            redis.delete(keys);
            keys.clear();
        }
    }

    @Test
    @DisplayName("已注册邮箱换发重置码：202、空响应体、投递一枚重置码、缓存只留摘要且 5 分钟")
    void registeredEmailReceivesResetCode() throws Exception {
        persistAccount(REGISTERED);

        requestResetCode(REGISTERED, captchaOf())
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        assertThat(outbox.messages()).hasSize(1);
        OutboxVerificationCodeSender.Message message = outbox.messages().get(0);
        assertThat(message.email()).isEqualTo(REGISTERED);
        assertThat(message.code()).matches("\\d{6}");
        assertThat(message.purpose()).isEqualTo(CodePurpose.PASSWORD_RESET);

        String key = resetCodes.keyOf(REGISTERED);
        assertThat(redis.opsForValue().get(key)).matches("[0-9a-f]{64}");
        // 「与注册码一致的 300 秒」是 spec 写死的约束；留一点余量给往返耗时。
        assertThat(redis.getExpire(key, TimeUnit.SECONDS))
                .isBetween(TTL_SECONDS - 30, TTL_SECONDS);
    }

    @Test
    @DisplayName("未注册邮箱请求重置码：202 与空响应体，但不投递、不落码")
    void unregisteredEmailGetsAcceptedResponseWithoutDelivery() throws Exception {
        requestResetCode(UNREGISTERED, captchaOf())
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        assertThat(outbox.messages()).isEmpty();
        assertThat(redis.hasKey(resetCodes.keyOf(UNREGISTERED))).isFalse();
    }

    @Test
    @DisplayName("未注册邮箱照样消耗冷却与配额：代价相同才不泄露账户是否存在")
    void unregisteredEmailStillPaysCooldownAndQuota() throws Exception {
        requestResetCode(UNREGISTERED, captchaOf()).andExpect(status().isAccepted());

        // 若这里不消耗，同一个邮箱就能反复请求、按「第几次冷却」反推注册状态。
        assertThat(redis.hasKey(resetCodes.cooldownKeyOf(UNREGISTERED))).isTrue();
        assertThat(redis.opsForValue().get(quota.keyOf(UNREGISTERED))).isEqualTo("1");
    }

    @Test
    @DisplayName("已注销邮箱视同未注册：202 但不投递、不落码")
    void deletedEmailIsTreatedAsUnregistered() throws Exception {
        Account old = persistAccount(DELETED);
        old.delete(Instant.now());
        accounts.save(old);

        requestResetCode(DELETED, captchaOf())
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        assertThat(outbox.messages()).isEmpty();
        assertThat(redis.hasKey(resetCodes.keyOf(DELETED))).isFalse();
    }

    @Test
    @DisplayName("逐字段相等：已注册与未注册邮箱的响应状态码与响应体完全一致")
    void registeredAndUnregisteredResponsesAreFieldByFieldEqual() throws Exception {
        persistAccount(REGISTERED);

        MvcResult registered = requestResetCode(REGISTERED, captchaOf()).andReturn();
        MvcResult unregistered = requestResetCode(UNREGISTERED, captchaOf()).andReturn();

        // 两条路径都走到这里，投递结论却相反——这个差别只能留在内部。
        assertThat(outbox.messages()).hasSize(1);
        assertThat(outbox.messages().get(0).email()).isEqualTo(REGISTERED);

        assertThat(unregistered.getResponse().getStatus())
                .isEqualTo(registered.getResponse().getStatus());
        assertThat(unregistered.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(registered.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("图形码错误：400 与 CAPTCHA_INVALID，不投递、不落码、也不消耗配额")
    void wrongCaptchaDeliversNothingAndSpendsNoQuota() throws Exception {
        persistAccount(REGISTERED);
        CaptchaIssue issue = captchaOf();

        String body = objectMapper.writeValueAsString(
                new PasswordResetCodeRequest(REGISTERED, issue.captchaId(), "WXYZ"));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/password-reset-code")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_INVALID"));

        assertThat(outbox.messages()).isEmpty();
        assertThat(redis.hasKey(resetCodes.keyOf(REGISTERED))).isFalse();
        assertThat(redis.hasKey(quota.keyOf(REGISTERED))).isFalse();
    }

    @Test
    @DisplayName("冷却跨用途共用：刚发过注册码的邮箱，60 秒内请求重置码收到 429")
    void cooldownIsSharedWithRegisterCode() throws Exception {
        // 冷却记在「同一邮箱」而不是「同一类码」上：分开记的话，连发两类码就能
        // 在 60 秒内寄出两封，冷却名存实亡。
        requestRegisterCode(UNREGISTERED, captchaOf()).andExpect(status().isAccepted());

        requestResetCode(UNREGISTERED, captchaOf())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("两类码的缓存键按用途分段：一个用途的码用不到另一个端点上去")
    void codeKeysAreSegmentedByPurpose() {
        String normalized = EmailNormalizer.normalize(REGISTERED);
        String resetKey = resetCodes.keyOf(normalized);
        String registerKey = registerCodes.keyOf(normalized);

        assertThat(resetKey).isNotEqualTo(registerKey);
        assertThat(resetKey).startsWith("wc:code:reset:");
        assertThat(registerKey).startsWith("wc:code:register:");
        // 键会出现在运维工具里，邮箱是个人信息，不能明文进键名。
        assertThat(resetKey).doesNotContain("ivy").doesNotContain("@");
    }

    private Account persistAccount(String email) {
        return accounts.save(Account.createActive(
                Ulid.generate(), email, "$2a$10$abcdefghijklmnopqrstuv", "Probe"));
    }

    private CaptchaIssue captchaOf() {
        CaptchaIssue issue = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());
        return issue;
    }

    private ResultActions requestResetCode(String email, CaptchaIssue issue) throws Exception {
        return post("/api/auth/password-reset-code",
                objectMapper.writeValueAsString(
                        new PasswordResetCodeRequest(email, issue.captchaId(), CAPTCHA_ANSWER)));
    }

    private ResultActions requestRegisterCode(String email, CaptchaIssue issue) throws Exception {
        return post("/api/auth/register-code",
                objectMapper.writeValueAsString(
                        new RegisterCodeRequest(email, issue.captchaId(), CAPTCHA_ANSWER)));
    }

    private ResultActions post(String path, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void resetCodeState(String email) {
        String normalized = EmailNormalizer.normalize(email);
        redis.delete(List.of(
                registerCodes.keyOf(normalized),
                registerCodes.failKeyOf(normalized),
                resetCodes.keyOf(normalized),
                resetCodes.failKeyOf(normalized),
                resetCodes.cooldownKeyOf(normalized),
                quota.keyOf(normalized)));
    }
}
