package com.luyi.myfirst.web.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.entity.AuthSession;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import com.luyi.myfirst.service.auth.model.IssuedToken;
import com.luyi.myfirst.service.auth.support.TokenIssuer;
import com.luyi.myfirst.web.auth.AuthCookie;
import com.luyi.myfirst.web.profile.dto.UpdateProfileRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 资料端点的 HTTP 契约（任务 2.4）：身份从 cookie 来、来源从 Origin 头来、DTO 映射正确、状态码对。
 *
 * 校验边界由 ProfileRules 钉死，这里只验证「拒绝经统一出口转成 400/401/403」与「响应字段白名单」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProfileControllerTest {

    private static final String ORIGIN = "http://localhost:3000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TokenIssuer issuer;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AuthSessionRepository sessions;

    @Test
    @DisplayName("未登录读本人资料：401 UNAUTHENTICATED")
    void readRequiresToken() throws Exception {
        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("未登录更新：401 且不写入")
    void updateRequiresTokenAndDoesNotWrite() throws Exception {
        Account account = seed("Alice");
        account.updateProfile("Alice", "https://a.example/a.png", "原简介", "hiking");
        accounts.saveAndFlush(account);

        mockMvc.perform(put("/api/profile")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateProfileRequest("Bob", null, "新简介", null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        Account after = accounts.findById(account.id()).orElseThrow();
        assertThat(after.displayName()).isEqualTo("Alice");
        assertThat(after.bio()).isEqualTo("原简介");
    }

    @Test
    @DisplayName("跨站来源更新：403 CROSS_SITE_REJECTED，先于认证")
    void crossSiteUpdateRejectedBeforeAuth() throws Exception {
        mockMvc.perform(put("/api/profile")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateProfileRequest("Bob", null, null, null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));
    }

    @Test
    @DisplayName("合法更新：200 返回保存后的四项资料")
    void validUpdateReturnsProfile() throws Exception {
        Cookie cookie = authCookie("Alice");

        mockMvc.perform(put("/api/profile")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateProfileRequest(
                                "Bob", "https://a.example/a.png", "简介", List.of("hiking", "food")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Bob"))
                .andExpect(jsonPath("$.avatarUrl").value("https://a.example/a.png"))
                .andExpect(jsonPath("$.bio").value("简介"))
                .andExpect(jsonPath("$.interests[0]").value("hiking"))
                .andExpect(jsonPath("$.interests[1]").value("food"));
    }

    @Test
    @DisplayName("400 的四种拒绝：空白昵称、昵称超长、简介超长、非法头像")
    void fourValidationRejections() throws Exception {
        Cookie cookie = authCookie("Alice");

        expectValidationFailed(cookie, new UpdateProfileRequest("   ", null, null, null));
        expectValidationFailed(cookie, new UpdateProfileRequest("a".repeat(65), null, null, null));
        expectValidationFailed(cookie, new UpdateProfileRequest(null, null, "a".repeat(201), null));
        expectValidationFailed(cookie, new UpdateProfileRequest(null, "ftp://cdn.example/a.png", null, null));
    }

    @Test
    @DisplayName("批量 51 个：400 VALIDATION_FAILED")
    void batchRejectsFiftyOne() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            ids.add(Ulid.generate());
        }

        mockMvc.perform(get("/api/profiles").param("publicIds", String.join(",", ids)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("名片响应恰含三字段，不含简介、兴趣标签、邮箱与状态")
    void cardResponseHasExactlyThreeFields() throws Exception {
        Account account = seed("CardAuthor");
        account.updateProfile("CardAuthor", "https://a.example/a.png", "不应出现", "hiking");
        accounts.saveAndFlush(account);

        String body = mockMvc.perform(get("/api/profiles").param("publicIds", account.publicId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode card = objectMapper.readTree(body).get("items").get(0);
        assertThat(fieldNames(card)).containsExactlyInAnyOrder("publicId", "displayName", "avatarUrl");
        assertThat(card.get("publicId").asText()).isEqualTo(account.publicId());
        assertThat(card.get("displayName").asText()).isEqualTo("CardAuthor");
    }

    // ---------- 夹具 ----------

    private void expectValidationFailed(Cookie cookie, UpdateProfileRequest request) throws Exception {
        mockMvc.perform(put("/api/profile")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private Account seed(String displayName) {
        String publicId = Ulid.generate();
        return accounts.saveAndFlush(Account.createActive(
                publicId, publicId.toLowerCase() + "@example.com", "hash", displayName));
    }

    private Cookie authCookie(String displayName) {
        String publicId = Ulid.generate();
        Account account = accounts.saveAndFlush(Account.createActive(
                publicId, publicId.toLowerCase() + "@example.com", "$2a$10$abcdefghijklmnopqrstuv", displayName));
        IssuedToken issued = issuer.issue(account, Instant.now());
        sessions.save(AuthSession.open(account.id(), issued.jti(), issued.expiresAt()));
        return new Cookie(AuthCookie.NAME, issued.value());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
