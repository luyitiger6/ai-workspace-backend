package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.web.auth.dto.RegisterCodeRequest;
import com.luyi.myfirst.web.auth.dto.RegisterRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 防枚举的契约（tasks 5.2）：注册链路的两步都不能让调用方分辨邮箱注册没注册。
 *
 * 两次响应必须「逐字段相等」，而不只是「状态码相同」：少一个字段、多一句提示，
 * 就等于把「这个邮箱已注册」直接写在响应里。
 *
 * 对照组是本类的一半——只断言「已注册邮箱不投递」而不断言「未注册邮箱投递」的话，
 * 一个把发信整段删掉的实现也能通过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RegisterEnumerationTest {

    private static final String REGISTERED = "erin@example.com";
    private static final String UNREGISTERED = "fay@example.com";
    private static final String DELETED = "gwen@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String CAPTCHA_ANSWER = "ABCD";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    @DisplayName("已注册邮箱请求注册码：202 且响应体为空，但不投递、不落码")
    void registeredEmailGetsSameAcceptedResponseWithoutDelivery() throws Exception {
        persistAccount(REGISTERED);

        CaptchaIssue issue = captchaOf();

        requestCode(REGISTERED, issue.captchaId())
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        assertThat(outbox.messages()).isEmpty();
        assertThat(redis.hasKey(registerCodes.keyOf(REGISTERED))).isFalse();
    }

    @Test
    @DisplayName("已注册邮箱照样消耗冷却与配额：两条路径的代价相同，探测不出额外信息")
    void registeredEmailStillPaysCooldownAndQuota() throws Exception {
        persistAccount(REGISTERED);

        requestCode(REGISTERED, captchaOf().captchaId()).andExpect(status().isAccepted());

        // 冷却键在、配额已计一次。若这里不消耗，同一个邮箱就能无限次探「注册没注册」。
        assertThat(redis.hasKey(registerCodes.cooldownKeyOf(REGISTERED))).isTrue();
        assertThat(redis.opsForValue().get(quota.keyOf(REGISTERED))).isEqualTo("1");
    }

    @Test
    @DisplayName("未注册邮箱照常收到码：对照组，证明「不投递」不是因为发信坏了")
    void unregisteredEmailStillReceivesTheCode() throws Exception {
        requestCode(UNREGISTERED, captchaOf().captchaId()).andExpect(status().isAccepted());

        assertThat(outbox.messages()).hasSize(1);
        assertThat(redis.hasKey(registerCodes.keyOf(UNREGISTERED))).isTrue();
    }

    @Test
    @DisplayName("已注册邮箱提交注册：响应与「注册码错误」逐字段相等")
    void submittingRegisterForRegisteredEmailLooksExactlyLikeWrongCode() throws Exception {
        persistAccount(REGISTERED);

        String wrongCode = register("hannah-unregistered@example.com", "000000")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        MvcResult alreadyRegistered = register(REGISTERED, "000000")
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(alreadyRegistered.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(wrongCode);
    }

    @Test
    @DisplayName("已注销邮箱：照常发码，也能重新注册")
    void deletedEmailCanRegisterAgain() throws Exception {
        Account old = persistDeletedAccount(DELETED);

        CaptchaIssue issue = captchaOf();
        requestCode(DELETED, issue.captchaId()).andExpect(status().isAccepted());

        assertThat(outbox.messages()).hasSize(1);
        String code = outbox.messages().get(0).code();

        register(DELETED, code).andExpect(status().isOk());

        Account fresh = accounts.findByEmailAndDeletedAtIsNull(DELETED).orElseThrow();
        assertThat(fresh.publicId()).isNotEqualTo(old.publicId());
        assertThat(old.status()).isEqualTo(AccountStatus.DELETED);
    }

    private Account persistAccount(String email) {
        return accounts.save(Account.createActive(
                Ulid.generate(), email, "$2a$10$abcdefghijklmnopqrstuv", "Probe"));
    }

    private Account persistDeletedAccount(String email) {
        Account account = persistAccount(email);
        account.delete(Instant.now());
        return accounts.save(account);
    }

    private CaptchaIssue captchaOf() {
        CaptchaIssue issue = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());
        return issue;
    }

    private ResultActions requestCode(String email, String captchaId) throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterCodeRequest(email, captchaId, CAPTCHA_ANSWER));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register-code")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions register(String email, String code) throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest(email, code, PASSWORD, null));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void resetCodeState(String email) {
        String normalized = EmailNormalizer.normalize(email);
        redis.delete(List.of(
                registerCodes.keyOf(normalized),
                registerCodes.failKeyOf(normalized),
                registerCodes.cooldownKeyOf(normalized),
                quota.keyOf(normalized)));
    }
}
