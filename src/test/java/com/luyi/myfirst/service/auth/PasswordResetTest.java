package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.PasswordResetCodeServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.web.auth.AuthCookie;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.dto.PasswordResetCodeRequest;
import com.luyi.myfirst.web.auth.dto.PasswordResetRequest;
import com.luyi.myfirst.web.auth.dto.RegisterCodeRequest;
import com.luyi.myfirst.web.auth.interceptor.SourceKeyResolver;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重置密码端点的契约（tasks 7.2 / 7.3 / 7.4）。
 *
 * 每条「密码没被改」的断言都用**真登录**来验证，而不是读库比对哈希：
 * 「旧密码被拒、新密码可立即登录」是 spec 的措辞，它说的是用户能不能进得去，
 * 不是字段变没变。读哈希只能证明写操作发生了，证明不了它真的生效。
 *
 * 旧令牌作废也用真请求验证（{@code GET /api/auth/me}，由 {@code AuthInterceptor} 走完
 * 验签 → jti → 账户 active 且 ver 一致三步），不在测试里直接调 {@code TokenVerifier}：
 * 7.3 的措辞是「下一次请求即被拒」，能证明这一点的只有一次请求。
 *
 * 码的三条失败分支（已用过、已过期、比对失败超限）各有一条用例，且一律以「旧密码仍能登录」
 * 收尾：三条分支在缓存看来都是同一件事（键不在了），区别只在「为什么不在」，
 * 而 spec 要求的是三者都不修改密码。
 *
 * 对照组同样是本类的一半：只断言「码错时不改密码」的话，一个什么都不做的实现也能通过，
 * 所以每条拒绝路径旁边都有一条「码对时真的改成了」。
 *
 * 来源键换成每个用例一个随机值（{@link SourceKeyResolver} 被换掉）：登录断言会制造
 * 失败计数，用固定的来源键会让本类自己把自己限流掉，还会牵连同一个 Redis 上的其他用例。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasswordResetTest {

    private static final String REGISTERED = "laura@example.com";
    private static final String UNREGISTERED = "mike@example.com";
    private static final String DELETED = "nina@example.com";

    private static final String OLD_PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "staple-quantum-battery";
    private static final String OTHER_PASSWORD = "zebra-lantern-anchor";

    private static final String ORIGIN = "http://localhost:3000";
    private static final String CAPTCHA_ANSWER = "ABCD";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";
    private static final String WRONG_CODE = "000999";

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
    private AccountRepository accounts;

    @Autowired
    private PasswordHasher hasher;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoBean
    private SourceKeyResolver sourceKey;

    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void isolateSource() {
        // 每个用例一个来源，登录失败计数互不牵连（TTL 到期后自行消失）。
        when(sourceKey.resolve(any())).thenReturn("reset-test-" + UUID.randomUUID());
        outbox.clear();
        resetCodeState(REGISTERED);
        resetCodeState(UNREGISTERED);
        resetCodeState(DELETED);
    }

    @AfterEach
    void cleanUp() {
        if (!keys.isEmpty()) {
            // 删的是加过前缀的真实键：删原始值等于空操作，计数会漏给下一个用例。
            keys.add(resetCodes.cooldownKeyOf(EmailNormalizer.normalize(REGISTERED)));
            keys.add(quota.keyOf(EmailNormalizer.normalize(REGISTERED)));
            keys.add(quota.keyOf(EmailNormalizer.normalize(UNREGISTERED)));
            keys.add(quota.keyOf(EmailNormalizer.normalize(DELETED)));
            redis.delete(keys);
            keys.clear();
        }
    }

    @Test
    @DisplayName("码对：200、空响应体，旧密码立刻登不进去、新密码立刻能登进去")
    void resetsPasswordSoTheOldOneStopsWorking() throws Exception {
        persistAccount(REGISTERED);
        String code = issueResetCode(REGISTERED);

        submitReset(REGISTERED, code, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        login(REGISTERED, OLD_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        login(REGISTERED, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("码是一次性的：同一枚码第二次提交 400 INVALID_CODE，密码停在第一次的结果")
    void codeIsConsumedByTheSuccessfulReset() throws Exception {
        persistAccount(REGISTERED);
        String code = issueResetCode(REGISTERED);

        submitReset(REGISTERED, code, NEW_PASSWORD).andExpect(status().isOk());

        // 键在验码成功的同一次往返里被删掉（CONSUME_IF_MATCH），不是等 TTL 到期。
        submitReset(REGISTERED, code, OTHER_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));

        // 第二次提交若真的改了密码，这里会登不进去。
        login(REGISTERED, NEW_PASSWORD).andExpect(status().isOk());
        // 反向那一半：第二次也没把密码改成它自己提交的那个。
        login(REGISTERED, OTHER_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("码错：400 INVALID_CODE 且密码不变——码不匹配时一行写操作都不该发生")
    void wrongCodeDoesNotChangeThePassword() throws Exception {
        persistAccount(REGISTERED);
        issueResetCode(REGISTERED);

        submitReset(REGISTERED, WRONG_CODE, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));

        login(REGISTERED, OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("未注册邮箱提交：响应与「重置码错误」逐字段相等")
    void unregisteredEmailLooksExactlyLikeWrongCode() throws Exception {
        persistAccount(REGISTERED);
        issueResetCode(REGISTERED);

        MvcResult wrongCode = submitReset(REGISTERED, WRONG_CODE, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andReturn();

        MvcResult unknownEmail = submitReset(UNREGISTERED, WRONG_CODE, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(unknownEmail.getResponse().getStatus())
                .isEqualTo(wrongCode.getResponse().getStatus());
        assertThat(unknownEmail.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(wrongCode.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("已注销邮箱提交：与码错误同形，且不复活旧账户")
    void deletedEmailCannotReset() throws Exception {
        Account old = persistAccount(DELETED);
        old.delete(Instant.now());
        accounts.save(old);

        submitReset(DELETED, WRONG_CODE, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));

        assertThat(accounts.findByEmailAndDeletedAtIsNull(DELETED)).isEmpty();
    }

    @Test
    @DisplayName("新密码太短：400 VALIDATION_FAILED，且不烧掉用户手里那枚码")
    void malformedPasswordIsRejectedWithoutBurningTheCode() throws Exception {
        persistAccount(REGISTERED);
        String code = issueResetCode(REGISTERED);

        // 格式校验排在验码之前：密码不合格是表单错误，不该让用户重走整条取码链路。
        submitReset(REGISTERED, code, "1234567")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        submitReset(REGISTERED, code, NEW_PASSWORD).andExpect(status().isOk());
        login(REGISTERED, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("重置后：重置前签发的每一枚会话在下一次请求即被拒——作废的是全部，不是最后一个")
    void resetRejectsEverySessionIssuedBeforeIt() throws Exception {
        persistAccount(REGISTERED);
        Cookie first = sessionCookie(REGISTERED, OLD_PASSWORD);
        Cookie second = sessionCookie(REGISTERED, OLD_PASSWORD);

        // 前置：两枚都是真能用的会话。少了这一句，一个「谁都不认」的实现也能让下面的 401 全绿。
        me(first).andExpect(status().isOk());
        me(second).andExpect(status().isOk());

        String code = issueResetCode(REGISTERED);
        submitReset(REGISTERED, code, NEW_PASSWORD).andExpect(status().isOk());

        // 两次登录拿到的是两条独立会话行，且各自带有当时的 ver；
        // 只有实际自增 token_version 才可能让它们在同一次请求里一起失效。
        me(first).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        me(second).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("重置后的新登录立刻可用——作废的是旧令牌，不是这个账户")
    void loginAfterResetIsAccepted() throws Exception {
        persistAccount(REGISTERED);
        String code = issueResetCode(REGISTERED);
        submitReset(REGISTERED, code, NEW_PASSWORD).andExpect(status().isOk());

        // 对照组：只断言「旧令牌死了」的话，一个把 token_version 加两次、
        // 或干脆拒掉所有请求的实现同样能通过。
        Cookie fresh = sessionCookie(REGISTERED, NEW_PASSWORD);
        me(fresh).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(REGISTERED));
    }

    @Test
    @DisplayName("码过期：拿着过期的码提交 400 INVALID_CODE，密码不变")
    void expiredCodeDoesNotChangeThePassword() throws Exception {
        persistAccount(REGISTERED);
        String code = issueResetCode(REGISTERED);

        // 过期在缓存里就是「键不在了」，与「从未发过」「已经用过」不可区分（见 EmailCodeFlow）。
        // 用删键模拟 TTL 到期而不是真等 5 分钟：这里要验的是过期之后的判定，不是计时器本身
        // ——「TTL 确实是 5 分钟」已由 7.1 的 [270,300] 断言钉住。
        redis.delete(resetCodes.keyOf(EmailNormalizer.normalize(REGISTERED)));

        submitReset(REGISTERED, code, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));

        login(REGISTERED, OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("连错 4 次：码仍然可用——阈值是 5，不是「错一次就作废」")
    void fourWrongAttemptsLeaveTheCodeUsable() throws Exception {
        persistAccount(REGISTERED);
        String code = issueResetCode(REGISTERED);

        for (int attempt = 1; attempt <= 4; attempt++) {
            submitReset(REGISTERED, WRONG_CODE, NEW_PASSWORD)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        }

        // 四次失败一次都没改密码。
        login(REGISTERED, OLD_PASSWORD).andExpect(status().isOk());

        // 对照：阈值没被提前。少了这一句，一个「错一次就作废」的实现也能让上面全绿。
        submitReset(REGISTERED, code, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("连错 5 次：码连同失败计数一起作废，之后正确的码也被拒、密码不变")
    void fifthWrongAttemptBurnsTheCodeEvenForTheRightOne() throws Exception {
        persistAccount(REGISTERED);
        String code = issueResetCode(REGISTERED);

        for (int attempt = 1; attempt <= 5; attempt++) {
            submitReset(REGISTERED, WRONG_CODE, NEW_PASSWORD)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        }

        String normalized = EmailNormalizer.normalize(REGISTERED);
        // 两个键一起被删：只删码而留着计数的话，下次发码时计数还挂着，阈值就提前了；
        // 只删计数而留着码的话，超限等于没发生过。
        assertThat(redis.hasKey(resetCodes.keyOf(normalized))).isFalse();
        assertThat(redis.hasKey(resetCodes.failKeyOf(normalized))).isFalse();

        submitReset(REGISTERED, code, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));

        login(REGISTERED, OLD_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("反复重置：每一轮的失败与成功都不改账户状态，当轮结束后都能用当时的密码登录")
    void repeatedResetsNeverLockTheAccount() throws Exception {
        persistAccount(REGISTERED);
        String password = OLD_PASSWORD;

        for (int round = 1; round <= 3; round++) {
            String next = "round-" + round + "-password";

            // 每轮先错后对：两条路径都走一遍，看状态会不会被其中某一路改写。
            submitReset(REGISTERED, WRONG_CODE, next)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CODE"));

            // 冷却只是发码节流，不属账户状态；要在一例里走完三轮就得把它清掉
            // —— 冷却本身的行为由 `PasswordResetCodeTest` 看守。
            redis.delete(resetCodes.cooldownKeyOf(EmailNormalizer.normalize(REGISTERED)));
            String code = issueResetCode(REGISTERED);
            submitReset(REGISTERED, code, next).andExpect(status().isOk());

            password = next;
            // 登录能成功就是「没被锁」的直接证据：login 只在账户 active 时才签发令牌。
            login(REGISTERED, password).andExpect(status().isOk());
            assertThat(accounts.findByEmailAndDeletedAtIsNull(REGISTERED).orElseThrow().status())
                    .isEqualTo(AccountStatus.ACTIVE);
        }

        Account reloaded = accounts.findByEmailAndDeletedAtIsNull(REGISTERED).orElseThrow();
        // 三轮各推进一次版本：重置只碰哈希与版本，不碰状态、不碰 deletedAt。
        assertThat(reloaded.tokenVersion()).isEqualTo(3);
        assertThat(reloaded.deletedAt()).isNull();
        login(REGISTERED, OLD_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("注册码与重置码共用日配额：注册额度用尽后重置码也是 429，且两者 Retry-After 指向同一时刻")
    void resetCodeSharesTheDailyQuotaWithRegisterCode() throws Exception {
        persistAccount(REGISTERED);
        String normalized = EmailNormalizer.normalize(REGISTERED);

        // 已注册邮箱请求注册码照常烧图形码、占日配额（5.2 的「消耗相同」），正好用来把额度用尽。
        for (int used = 1; used <= 5; used++) {
            clearCooldown(REGISTERED);
            requestRegisterCode(REGISTERED).andExpect(status().isAccepted());
        }
        assertThat(redis.opsForValue().get(quota.keyOf(normalized))).isEqualTo("5");

        clearCooldown(REGISTERED);
        MvcResult exhaustedRegister = requestRegisterCode(REGISTERED)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andReturn();

        MvcResult exhaustedReset = requestResetCode(REGISTERED)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andReturn();

        String registerRetryAfter = exhaustedRegister.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        String resetRetryAfter = exhaustedReset.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(registerRetryAfter).isNotNull();
        // 两个端点算的是同一个「次日零点」，不是各自从此刻起数一个窗口：
        // 一份自然日额度只能有一个恢复时刻。
        assertThat(resetRetryAfter).isEqualTo(registerRetryAfter);
        assertThat(Long.parseLong(registerRetryAfter)).isBetween(1L, 86_400L);

        // 配额挡的是发码，不是账户：旧密码照样能登录。
        login(REGISTERED, OLD_PASSWORD).andExpect(status().isOk());
    }

    private Account persistAccount(String email) {
        return accounts.save(Account.createActive(
                Ulid.generate(), email, hasher.hash(OLD_PASSWORD), "Probe"));
    }

    private CaptchaIssue captchaOf() {
        CaptchaIssue issue = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());
        return issue;
    }

    /** 走真实端点取码，明文码从 outbox 读——它是唯一能看到码的地方（D8）。 */
    private String issueResetCode(String email) throws Exception {
        requestResetCode(email).andExpect(status().isAccepted());

        List<OutboxVerificationCodeSender.Message> messages = outbox.messages();
        assertThat(messages).isNotEmpty();
        return messages.get(messages.size() - 1).code();
    }

    /** 重置码请求本身（状态码由调用方断言：7.6 要断言 429，取码用例要断言 202）。 */
    private ResultActions requestResetCode(String email) throws Exception {
        CaptchaIssue issue = captchaOf();
        String body = objectMapper.writeValueAsString(
                new PasswordResetCodeRequest(email, issue.captchaId(), CAPTCHA_ANSWER));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/password-reset-code")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions requestRegisterCode(String email) throws Exception {
        CaptchaIssue issue = captchaOf();
        String body = objectMapper.writeValueAsString(
                new RegisterCodeRequest(email, issue.captchaId(), CAPTCHA_ANSWER));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register-code")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** 冷却键跨用途共用；要连续发码就得把它清掉（冷却本身的行为由 7.1 看守）。 */
    private void clearCooldown(String email) {
        redis.delete(registerCodes.cooldownKeyOf(EmailNormalizer.normalize(email)));
    }

    private ResultActions submitReset(String email, String code, String newPassword) throws Exception {
        String body = objectMapper.writeValueAsString(
                new PasswordResetRequest(email, code, newPassword));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/password-reset")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions login(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest(email, password, null, null));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** 一次真登录会话的 cookie。断言非空，否则后面的 401 可能只是「根本没带令牌」。 */
    private Cookie sessionCookie(String email, String password) throws Exception {
        MvcResult result = login(email, password).andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie(AuthCookie.NAME);
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private ResultActions me(Cookie cookie) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").cookie(cookie));
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
