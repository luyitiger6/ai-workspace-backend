package com.luyi.myfirst.web.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开用户资料端点的 HTTP 契约（任务 1.5）：游客可读、字段白名单、三种未找到响应逐字相同。
 *
 * 不带 cookie 发请求就是「游客」——拦截器若没把这条路径登记为公开，用例会收到 401 而不是 200。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accounts;

    @Test
    @DisplayName("游客读取：200 且恰含五项公开字段")
    void guestReadsFiveFields() throws Exception {
        Account account = seed("Alice");
        account.updateProfile("Alice", "https://a.example/a.png", "简介", "hiking,food");
        accounts.saveAndFlush(account);

        String body = mockMvc.perform(get("/api/profiles/{publicId}", account.publicId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode profile = objectMapper.readTree(body);
        assertThat(fieldNames(profile))
                .containsExactlyInAnyOrder("publicId", "displayName", "avatarUrl", "bio", "interests");
        assertThat(profile.get("publicId").asText()).isEqualTo(account.publicId());
        assertThat(profile.get("displayName").asText()).isEqualTo("Alice");
        assertThat(profile.get("bio").asText()).isEqualTo("简介");
        assertThat(profile.get("interests").get(0).asText()).isEqualTo("hiking");
        assertThat(profile.get("interests").get(1).asText()).isEqualTo("food");
    }

    @Test
    @DisplayName("未填写的资料：空串与空数组，字段不省略")
    void unwrittenFieldsStayPresent() throws Exception {
        Account account = seed("Blank");

        mockMvc.perform(get("/api/profiles/{publicId}", account.publicId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(""))
                .andExpect(jsonPath("$.bio").value(""))
                .andExpect(jsonPath("$.interests").isEmpty());
    }

    @Test
    @DisplayName("不存在、已注销与形状不合：都是 404，响应体逐字相同")
    void threeMissesShareOneResponse() throws Exception {
        Account deleted = seed("Deleted");
        deleted.delete(Instant.now());
        accounts.saveAndFlush(deleted);

        String unknown = notFoundBody(Ulid.generate());
        String shapeBad = notFoundBody("not-a-ulid");
        String gone = notFoundBody(deleted.publicId());

        assertThat(unknown).isEqualTo(shapeBad).isEqualTo(gone);
        assertThat(objectMapper.readTree(unknown).get("code").asText()).isEqualTo("PROFILE_NOT_FOUND");
    }

    // ---------- 夹具 ----------

    private String notFoundBody(String publicId) throws Exception {
        return mockMvc.perform(get("/api/profiles/{publicId}", publicId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
    }

    private Account seed(String displayName) {
        String publicId = Ulid.generate();
        return accounts.saveAndFlush(Account.createActive(
                publicId, publicId.toLowerCase() + "@example.com", "hash", displayName));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
