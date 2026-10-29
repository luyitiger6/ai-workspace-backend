package com.luyi.myfirst.web.post.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.web.ApiResponse;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.entity.AuthSession;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import com.luyi.myfirst.service.auth.model.IssuedToken;
import com.luyi.myfirst.service.auth.support.TokenIssuer;
import com.luyi.myfirst.web.auth.AuthCookie;
import com.luyi.myfirst.web.post.dto.PostCreateRequest;
import com.luyi.myfirst.web.post.interaction.dto.BookmarkResponse;
import com.luyi.myfirst.web.post.interaction.dto.CommentCreateRequest;
import com.luyi.myfirst.web.post.interaction.dto.CommentListResponse;
import com.luyi.myfirst.web.post.interaction.dto.CommentResponse;
import com.luyi.myfirst.web.post.interaction.dto.VoteCreateRequest;
import com.luyi.myfirst.web.post.interaction.dto.VoteResponse;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 互动端点的 HTTP 契约（spec 中需 MockMvc 表达的部分）。
 *
 * 状态码、错误码、来源校验、字段白名单在这里钉住；
 * 业务状态变化由 {@link com.luyi.myfirst.service.post.interaction.PostInteractionServiceTest} 覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostInteractionControllerTest {

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

    // ---------- 白名单与端点存在性 ----------

    @Test
    @DisplayName("互动响应 DTO 实现 ApiResponse 标记接口")
    void interactionResponsesImplementApiResponse() {
        assertThat(CommentResponse.class.getInterfaces()).contains(ApiResponse.class);
        assertThat(VoteResponse.class.getInterfaces()).contains(ApiResponse.class);
        assertThat(BookmarkResponse.class.getInterfaces()).contains(ApiResponse.class);
        assertThat(CommentListResponse.class.getInterfaces()).contains(ApiResponse.class);
    }

    @Test
    @DisplayName("评论详情字段等于白名单")
    void commentResponseFieldsMatchWhitelist() {
        Set<String> whitelist = Set.of(
                "publicId", "postPublicId", "parentPublicId", "authorPublicId",
                "content", "createdAt", "updatedAt");
        Set<String> actual = recordComponentNames(CommentResponse.class);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(whitelist);
    }

    @Test
    @DisplayName("投票详情字段等于白名单")
    void voteResponseFieldsMatchWhitelist() {
        Set<String> whitelist = Set.of(
                "publicId", "postPublicId", "authorPublicId",
                "direction", "createdAt", "updatedAt");
        Set<String> actual = recordComponentNames(VoteResponse.class);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(whitelist);
    }

    @Test
    @DisplayName("收藏详情字段等于白名单")
    void bookmarkResponseFieldsMatchWhitelist() {
        Set<String> whitelist = Set.of(
                "publicId", "postPublicId", "authorPublicId", "createdAt", "updatedAt");
        Set<String> actual = recordComponentNames(BookmarkResponse.class);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(whitelist);
    }

    // ---------- 评论端点 ----------

    @Test
    @DisplayName("发表评论：同源+有效令牌 → 201，返回白名单字段")
    void createComment() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");

        mockMvc.perform(post("/api/posts/{postId}/comments", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CommentCreateRequest("写得好", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.publicId").isString())
                .andExpect(jsonPath("$.postPublicId").value(postId))
                .andExpect(jsonPath("$.parentPublicId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.authorPublicId").isString())
                .andExpect(jsonPath("$.content").value("写得好"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    @DisplayName("回复评论：返回 parentPublicId")
    void replyComment() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");
        String parentId = createCommentReturningId(postId, cookie, "顶层");

        mockMvc.perform(post("/api/posts/{postId}/comments", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CommentCreateRequest("回复", parentId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentPublicId").value(parentId));
    }

    @Test
    @DisplayName("空评论内容：400 VALIDATION_FAILED")
    void blankCommentIsRejected() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");

        mockMvc.perform(post("/api/posts/{postId}/comments", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CommentCreateRequest("   ", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("对不存在的帖子发表评论：404 POST_NOT_FOUND")
    void createCommentOnMissingPost() throws Exception {
        Cookie cookie = authCookie("作者甲");

        mockMvc.perform(post("/api/posts/{postId}/comments", "missingpostid0000000000001")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CommentCreateRequest("x", null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    }

    @Test
    @DisplayName("作者删除评论：204")
    void authorDeletesComment() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");
        String commentId = createCommentReturningId(postId, cookie, "将被删除");

        mockMvc.perform(delete("/api/posts/{postId}/comments/{commentId}", postId, commentId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("非作者删除评论：403 POST_FORBIDDEN")
    void nonAuthorCannotDeleteComment() throws Exception {
        Cookie authorCookie = authCookie("作者甲");
        Cookie otherCookie = authCookie("路人乙");
        String postId = createPostReturningId(authorCookie, "成都三日");
        String commentId = createCommentReturningId(postId, authorCookie, "留着");

        mockMvc.perform(delete("/api/posts/{postId}/comments/{commentId}", postId, commentId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(otherCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POST_FORBIDDEN"));
    }

    @Test
    @DisplayName("删除不存在的评论：404 POST_NOT_FOUND")
    void deleteMissingComment() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");

        mockMvc.perform(delete("/api/posts/{postId}/comments/{commentId}", postId, "missingcomment0000000001")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    }

    @Test
    @DisplayName("评论列表公开：游客可访问，返回 items 与 nextCursor")
    void listCommentsIsPublic() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");
        createCommentReturningId(postId, cookie, "一条评论");

        mockMvc.perform(get("/api/posts/{postId}/comments", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].content").value("一条评论"))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---------- 投票端点 ----------

    @Test
    @DisplayName("首次投票：201，方向 up")
    void firstVoteIsCreated() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");

        mockMvc.perform(post("/api/posts/{postId}/votes", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new VoteCreateRequest("up"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("up"));
    }

    @Test
    @DisplayName("重复投票：200，方向替换为 down")
    void repeatedVoteIsReplaced() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");
        vote(postId, cookie, "up");

        mockMvc.perform(post("/api/posts/{postId}/votes", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new VoteCreateRequest("down"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("down"));
    }

    @Test
    @DisplayName("非法投票方向：400 VALIDATION_FAILED")
    void invalidVoteDirectionIsRejected() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");

        mockMvc.perform(post("/api/posts/{postId}/votes", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new VoteCreateRequest("sideways"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("取消投票：204")
    void cancelVote() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");
        vote(postId, cookie, "up");

        mockMvc.perform(delete("/api/posts/{postId}/votes", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("取消不存在的投票：404 POST_NOT_FOUND")
    void cancelMissingVote() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");

        mockMvc.perform(delete("/api/posts/{postId}/votes", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    }

    // ---------- 收藏端点 ----------

    @Test
    @DisplayName("首次收藏：201")
    void firstBookmarkIsCreated() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");

        mockMvc.perform(post("/api/posts/{postId}/bookmarks", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postPublicId").value(postId));
    }

    @Test
    @DisplayName("重复收藏：200 幂等")
    void repeatedBookmarkIsOk() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");
        bookmark(postId, cookie);

        mockMvc.perform(post("/api/posts/{postId}/bookmarks", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("取消收藏：204")
    void cancelBookmark() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String postId = createPostReturningId(cookie, "成都三日");
        bookmark(postId, cookie);

        mockMvc.perform(delete("/api/posts/{postId}/bookmarks", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie))
                .andExpect(status().isNoContent());
    }

    // ---------- 来源校验与认证 ----------

    @Test
    @DisplayName("跨站写请求且缺令牌：403 CROSS_SITE_REJECTED，不是 401")
    void crossSiteWriteWithoutTokenIsForbidden() throws Exception {
        String postId = "postid0000000000000000001";

        mockMvc.perform(post("/api/posts/{postId}/comments", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CommentCreateRequest("x", null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));

        mockMvc.perform(post("/api/posts/{postId}/votes", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new VoteCreateRequest("up"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));

        mockMvc.perform(post("/api/posts/{postId}/bookmarks", postId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));
    }

    @Test
    @DisplayName("同源写请求但缺令牌：401 UNAUTHENTICATED")
    void sameSiteWriteWithoutTokenIsUnauthorized() throws Exception {
        String postId = "postid0000000000000000001";

        mockMvc.perform(post("/api/posts/{postId}/comments", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CommentCreateRequest("x", null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/api/posts/{postId}/votes", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new VoteCreateRequest("up"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/api/posts/{postId}/bookmarks", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- 夹具 ----------

    private String createPostReturningId(Cookie cookie, String title) throws Exception {
        String response = mockMvc.perform(post("/api/posts")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PostCreateRequest(title, "正文", null, List.of(), "published"))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("publicId").asText();
    }

    private String createCommentReturningId(String postId, Cookie cookie, String content) throws Exception {
        String response = mockMvc.perform(post("/api/posts/{postId}/comments", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CommentCreateRequest(content, null))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("publicId").asText();
    }

    private void vote(String postId, Cookie cookie, String direction) throws Exception {
        mockMvc.perform(post("/api/posts/{postId}/votes", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new VoteCreateRequest(direction))))
                .andExpect(status().isCreated());
    }

    private void bookmark(String postId, Cookie cookie) throws Exception {
        mockMvc.perform(post("/api/posts/{postId}/bookmarks", postId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie))
                .andExpect(status().isCreated());
    }

    private Cookie authCookie(String displayName) {
        String publicId = com.luyi.myfirst.common.id.Ulid.generate();
        Account account = accounts.saveAndFlush(Account.createActive(
                publicId,
                publicId.toLowerCase() + "@example.com",
                "$2a$10$abcdefghijklmnopqrstuv",
                displayName));
        IssuedToken issued = issuer.issue(account, Instant.now());
        sessions.save(AuthSession.open(account.id(), issued.jti(), issued.expiresAt()));
        return new Cookie(AuthCookie.NAME, issued.value());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static Set<String> recordComponentNames(Class<?> recordClass) {
        return Stream.of(recordClass.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());
    }
}
