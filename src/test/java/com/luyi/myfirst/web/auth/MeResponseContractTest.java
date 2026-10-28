package com.luyi.myfirst.web.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.interceptor.SourceKeyResolver;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/auth/me} 的响应字段契约（tasks 6.2 / 6.3 / 6.4）。
 *
 * spec 的要求分三层，本类各钉一层：
 * ①「字段集合 MUST 恰为 publicId/email/displayName/status/createdAt」——用一份白名单加一个
 *   递归字段路径收集器，断言实际返回的每个字段路径都被白名单覆盖，且差集为空；
 * ②「MUST NOT 出现 password_hash/salt/verification_code（任意嵌套）」——收集器走遍嵌套，
 *   逐个路径比对这三个敏感键；
 * ③「MUST NOT 包含已存储的密码哈希值」——直接拿账户真实哈希去响应原文里检索。
 *
 * 为什么要有收集器而不是像 {@code AccountEndpointsTest} 那样只列顶层字段名：顶层列名只能覆盖
 * 扁平响应，一旦哪天 {@code /me} 塞进一个嵌套对象（如 {@code "account":{...}}），敏感键就能藏在
 * 第二层绕过顶层断言。收集器按路径递归，嵌套多深都逃不掉——这正是 spec 括注「任意嵌套层级同样适用」
 * 的落地方式。也因为这个，本类专门有一条用例喂一个嵌套 JSON 给收集器，证明它真的会往里走：
 * {@code /me} 今天是扁平的，收集器即便写坏了（只收顶层）也一样能过白名单断言，那条用例就是防这个的。
 *
 * 反向验证（6.4 明写）：把真实哈希刻意拼进一个响应体，断言同一个检测器必须抓住它。
 * 没有这条，「响应不含哈希」可能只是一句永远为真的空断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MeResponseContractTest {

    private static final String EMAIL = "me-contract@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String ORIGIN = "http://localhost:3000";

    /** 6.2 的白名单：spec「字段集合 MUST 恰为」这五项，多一个少一个都算违约。 */
    private static final Set<String> ME_FIELD_WHITELIST =
            Set.of("publicId", "email", "displayName", "status", "createdAt");

    /**
     * spec 点名 MUST NOT 出现的三个键。库里是 snake_case，序列化到 JSON 通常是 camelCase，
     * 两种写法都算命中，所以比对前先归一化（转小写、去下划线）。
     */
    private static final Set<String> FORBIDDEN_KEY_TOKENS =
            Set.of("passwordhash", "salt", "verificationcode");

    /** bcrypt 哈希的前缀片段：整串哈希被截断只留前缀也算泄漏，一并挡住。 */
    private static final Set<String> BCRYPT_PREFIXES = Set.of("$2a$", "$2b$", "$2y$");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PasswordHasher hasher;

    @MockitoBean
    private SourceKeyResolver sourceKey;

    /** 最近一次登录账户的密码哈希，供「响应不得回显哈希」的断言取值。 */
    private String storedHash;

    @BeforeEach
    void isolateSource() {
        when(sourceKey.resolve(any())).thenReturn("me-contract-" + UUID.randomUUID());
    }

    @Test
    @DisplayName("6.2 收集器能走进嵌套对象与数组：/me 是扁平的，收集器写坏了也看不出来，这条专门防它")
    void collectorWalksNestedObjectsAndArrays() throws Exception {
        JsonNode nested = objectMapper.readTree(
                "{\"a\":\"x\",\"user\":{\"email\":\"e\",\"roles\":[{\"name\":\"r\"},\"plain\"]}}");

        assertThat(fieldPaths(nested)).containsExactlyInAnyOrder(
                "a", "user", "user.email", "user.roles", "user.roles[].name");
    }

    @Test
    @DisplayName("6.2 白名单覆盖 /me 返回的每一个字段路径")
    void whitelistCoversEveryFieldPathTheMeEndpointReturns() throws Exception {
        Set<String> paths = fieldPaths(objectMapper.readTree(loginAndGetMeBody()));

        // 「覆盖」= 实际返回的每个路径都在白名单里。反过来（白名单每个都在实际里）由 6.3 的差集断言负责。
        assertThat(ME_FIELD_WHITELIST)
                .as("白名单必须覆盖 /me 实际返回的全部字段路径")
                .containsAll(paths);
    }

    @Test
    @DisplayName("6.3 /me 字段路径差集为空，且任意嵌套都不含 password_hash / salt / verification_code")
    void meFieldPathsAreExactlyWhitelistedWithNoSensitiveKeys() throws Exception {
        Set<String> paths = fieldPaths(objectMapper.readTree(loginAndGetMeBody()));

        // 差集为空：没有任何未登记字段。「恰为」的两个方向——白名单⊇实际（6.2 已断）与实际⊇白名单都在这里收口。
        Set<String> unregistered = new LinkedHashSet<>(paths);
        unregistered.removeAll(ME_FIELD_WHITELIST);
        assertThat(unregistered)
                .as("出现了白名单未登记的字段路径，视为违反 Requirement")
                .isEmpty();

        Set<String> missing = new LinkedHashSet<>(ME_FIELD_WHITELIST);
        missing.removeAll(paths);
        assertThat(missing)
                .as("白名单里有字段没被返回，契约与实现不一致")
                .isEmpty();

        // 敏感键在任意嵌套层级都不得出现——即便差集为空已经能挡住它们，这里再点名断言一次，
        // 让「白名单被误改成含敏感键」这种情形也直接红，而不是靠差集间接发现。
        for (String path : paths) {
            String normalized = path.toLowerCase().replace("_", "");
            for (String token : FORBIDDEN_KEY_TOKENS) {
                assertThat(normalized)
                        .as("字段路径 %s 命中敏感键 %s", path, token)
                        .doesNotContain(token);
            }
        }
    }

    @Test
    @DisplayName("6.4 /me 响应原文检索不到该账户已存储的密码哈希字面值")
    void meBodyDoesNotEchoTheStoredPasswordHash() throws Exception {
        String body = loginAndGetMeBody();

        assertCarriesNoHash(body, storedHash);
        // 明文密码同样不得出现：哈希不可还原不等于原文可以被回显。
        assertThat(body)
                .as("/me 响应不得回显明文密码")
                .doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("6.4 反证：把真实哈希刻意拼进响应体，同一个检测器必须抓住，否则上一条是空断言")
    void hashLeakDetectorIsSensitiveToAnActualLeak() {
        String hash = hasher.hash(PASSWORD);
        // 刻意构造一个泄漏了哈希的响应体：检测器若放过它，说明 6.4 的断言根本没有检验力。
        String leakingBody = "{\"publicId\":\"x\",\"passwordHash\":\"" + hash + "\"}";

        assertThatThrownBy(() -> assertCarriesNoHash(leakingBody, hash))
                .isInstanceOf(AssertionError.class);
    }

    /** 响应原文不得含已存哈希整串，也不得含任何 bcrypt 前缀片段。 */
    private void assertCarriesNoHash(String body, String hash) {
        assertThat(body)
                .as("响应原文不得含已存储的密码哈希字面值")
                .doesNotContain(hash);
        for (String prefix : BCRYPT_PREFIXES) {
            assertThat(body)
                    .as("响应原文不得含 bcrypt 哈希前缀片段 %s", prefix)
                    .doesNotContain(prefix);
        }
    }

    /**
     * 递归收集 JSON 里每一个对象字段的路径：对象字段用 {@code .} 连接，数组元素用 {@code []} 标记。
     * 标量叶子不单独成路径（它没有字段名）；数组本身作为一个字段名已在父层被收集。
     */
    private static Set<String> fieldPaths(JsonNode root) {
        Set<String> paths = new LinkedHashSet<>();
        collectFieldPaths(root, "", paths);
        return paths;
    }

    private static void collectFieldPaths(JsonNode node, String prefix, Set<String> out) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                out.add(path);
                collectFieldPaths(field.getValue(), path, out);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                collectFieldPaths(element, prefix + "[]", out);
            }
        }
    }

    /** 建一个 active 账户、真实登录拿到 cookie、再请求 /me，返回响应原文；顺带记下密码哈希。 */
    private String loginAndGetMeBody() throws Exception {
        storedHash = hasher.hash(PASSWORD);
        Account account = Account.createActive(Ulid.generate(), EMAIL, storedHash, "Mira");
        accounts.saveAndFlush(account);

        MvcResult login = mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(EMAIL, PASSWORD, null, null))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = login.getResponse().getCookie(AuthCookie.NAME);
        assertThat(cookie).as("登录成功应当下发认证 cookie").isNotNull();

        MvcResult me = mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn();
        return me.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
