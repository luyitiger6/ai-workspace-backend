package com.luyi.myfirst.web.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.PostStatus;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.post.entity.Post;
import com.luyi.myfirst.dao.post.repository.PostRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户页帖子列表端点的 HTTP 契约（任务 2.3）：游客可读、三态码对、条目形状与首页 feed 一致。
 *
 * 不带 cookie 发请求就是游客——拦截器若没登记这条路径，200 的用例会先收到 401。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserPostsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PostRepository posts;

    @Test
    @DisplayName("游客读列表：200，只含该用户已发布，末页 nextCursor 为 null")
    void guestReadsAuthorFeed() throws Exception {
        Account author = seedAccount();
        Post published = save(author.id(), PostStatus.PUBLISHED);
        save(author.id(), PostStatus.DRAFT);

        String body = mockMvc.perform(get("/api/profiles/{publicId}/posts", author.publicId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode page = objectMapper.readTree(body);
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("publicId").asText()).isEqualTo(published.publicId());
        assertThat(page.get("items").get(0).get("authorPublicId").asText()).isEqualTo(author.publicId());
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("未知作者：404 PROFILE_NOT_FOUND")
    void unknownAuthorIsNotFound() throws Exception {
        mockMvc.perform(get("/api/profiles/{publicId}/posts", Ulid.generate()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
    }

    @Test
    @DisplayName("非法游标：400 VALIDATION_FAILED")
    void brokenCursorIsBadRequest() throws Exception {
        Account author = seedAccount();

        mockMvc.perform(get("/api/profiles/{publicId}/posts", author.publicId())
                        .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- 夹具 ----------

    private Account seedAccount() {
        String publicId = Ulid.generate();
        return accounts.saveAndFlush(Account.createActive(
                publicId, publicId.toLowerCase() + "@example.com", "hash", "作者"));
    }

    private Post save(Long authorId, PostStatus status) {
        return posts.saveAndFlush(Post.create(
                Ulid.generate(), authorId, "标题", "正文", null, List.of(), status, Instant.now()));
    }
}
