package com.luyi.myfirst.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `deleted` 是账户行的终态（tasks 2.5）。
 *
 * 已有覆盖的分工：{@code AccountTest} 在实体层证明 {@code delete()} 拒绝二次注销；
 * {@code LoginTest} 证明已注销账户登录返回 401；{@code RegisterEnumerationTest} 证明已注销邮箱
 * 能重新注册出新 publicId。本类补它们都没有的那件事——**把登录、取码、注册三步组合打在同一个
 * 已注销行上，并在每一步之后断言该行一个字段都没被改写**（status / publicId / deletedAt /
 * tokenVersion / version 全部逐字不变）。单看任一端点「没改状态」不足以说明终态：终态的意思是
 * 任何端点组合都改不动它。
 *
 * 另有一条枚举层用例把「终态」表达成机器可查的不变量：{@code DELETED} 对三个目标状态的
 * {@code canTransitionTo} 一律为 false，即它没有任何出边。
 *
 * 库内断言靠 {@code @Transactional} 回滚；Redis 键自己登记、自己删。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DeletedTerminalStateTest {

    private static final String EMAIL = "terminal@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String CAPTCHA_ANSWER = "ABCD";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RegisterCodeServiceImpl registerCodes;
    @Autowired private CaptchaServiceImpl captcha;
    @Autowired private OutboxVerificationCodeSender outbox;
    @Autowired private EmailQuotaGuard quota;
    @Autowired private StringRedisTemplate redis;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordHasher hasher;

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
    @DisplayName("登录 + 取码 + 注册组合打在已注销行上：每一步之后该行都逐字不变，注册只新增一行")
    void deletedRowIsNeverRewrittenByTheLoginRegisterCodeRegisterCombination() throws Exception {
        Account old = persistDeletedAccount(EMAIL);
        Long oldId = old.id();
        String oldPublicId = old.publicId();
        Instant oldDeletedAt = old.deletedAt();
        int oldTokenVersion = old.tokenVersion();
        int oldVersion = old.version();

        // 登录：deleted 落在「查不到」分支 → 401 UNAUTHENTICATED，无 cookie，且旧行不动。
        login(EMAIL, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        assertDeletedRowUnchanged(oldId, oldPublicId, oldDeletedAt, oldTokenVersion, oldVersion);

        // 取码：deleted 视同「无 active 账户」→ 202 且照常投递，旧行仍不动。
        CaptchaIssue issue = captchaOf();
        requestCode(EMAIL, issue.captchaId()).andExpect(status().isAccepted());
        assertThat(outbox.messages()).hasSize(1);
        assertDeletedRowUnchanged(oldId, oldPublicId, oldDeletedAt, oldTokenVersion, oldVersion);

        // 注册：拿 outbox 里的码建出新账户行（新 publicId、active），旧行仍是终态 deleted。
        String code = outbox.messages().get(0).code();
        register(EMAIL, code).andExpect(status().isOk());

        Account fresh = accounts.findByEmailAndDeletedAtIsNull(EMAIL).orElseThrow();
        assertThat(fresh.publicId()).as("重新注册必须拿到新的对外标识").isNotEqualTo(oldPublicId);
        assertThat(fresh.status()).isEqualTo(AccountStatus.ACTIVE);
        assertDeletedRowUnchanged(oldId, oldPublicId, oldDeletedAt, oldTokenVersion, oldVersion);

        // 该邮箱现在恰有两行：一条 deleted（旧）+ 一条 active（新）；终态约束的是行，不是邮箱。
        long rows = accounts.findAll().stream().filter(a -> EMAIL.equals(a.email())).count();
        assertThat(rows).isEqualTo(2);
    }

    @Test
    @DisplayName("枚举层：deleted 对任何目标状态都没有出边，即终态")
    void deletedIsTerminalForEveryTargetStatus() {
        for (AccountStatus target : AccountStatus.values()) {
            assertThat(AccountStatus.DELETED.canTransitionTo(target))
                    .as("deleted 是终态，不得迁移到 %s", target)
                    .isFalse();
        }
    }

    private void assertDeletedRowUnchanged(Long id, String publicId, Instant deletedAt,
                                           int tokenVersion, int version) {
        Account reloaded = accounts.findById(id).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(AccountStatus.DELETED);
        assertThat(reloaded.publicId()).isEqualTo(publicId);
        assertThat(reloaded.deletedAt()).isEqualTo(deletedAt);
        assertThat(reloaded.tokenVersion()).isEqualTo(tokenVersion);
        assertThat(reloaded.version()).isEqualTo(version);
    }

    private Account persistDeletedAccount(String email) {
        Account account = accounts.saveAndFlush(
                Account.createActive(Ulid.generate(), email, hasher.hash(PASSWORD), "Terminal"));
        account.delete(Instant.now());
        return accounts.saveAndFlush(account);
    }

    private CaptchaIssue captchaOf() {
        CaptchaIssue issue = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());
        return issue;
    }

    private ResultActions login(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest(email, password, null, null));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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
}
