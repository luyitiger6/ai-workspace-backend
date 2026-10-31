package com.luyi.myfirst.web.post.interaction;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    @Test
    @DisplayName("详情和评论 GET 可见性一致：草稿作者可读，他人、游客、无效 cookie 为 404")
    void attachedReadsShareDraftVisibility() throws Exception {
        Cookie author = authCookie("草稿作者");
        Cookie other = authCookie("其他账户");
        Cookie invalid = new Cookie(AuthCookie.NAME, "invalid-token");
        String publicId = createPostReturningId(author, "可见性");
        createCommentReturningId(publicId, author, "附属内容");
        for (Cookie viewer : new Cookie[]{null, author, other, invalid}) {
            assertPublicReadsStatus(publicId, viewer, 200);
        }
        mockMvc.perform(put("/api/posts/{publicId}", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PostCreateRequest("草稿", "内容", null, List.of(), "draft"))))
                .andExpect(status().isOk());
        assertPublicReadsStatus(publicId, author, 200);
        for (Cookie viewer : new Cookie[]{null, other, invalid}) {
            assertPublicReadsStatus(publicId, viewer, 404);
        }
        mockMvc.perform(delete("/api/posts/{publicId}", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(author))
                .andExpect(status().isNoContent());
        for (Cookie viewer : new Cookie[]{null, author, other}) {
            assertPublicReadsStatus(publicId, viewer, 404);
            assertPublicReadsStatus(com.luyi.myfirst.common.id.Ulid.generate(), viewer, 404);
        }
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

    @Test
    @DisplayName("个人状态 HTTP 为准确两字段且 no-store，客户端账户参数不改变 cookie 归属")
    void personalStateJsonIsPrivateAndAccountBound() throws Exception {
        Cookie a = authCookie("账户甲");
        Cookie b = authCookie("账户乙");
        String publicId = createPostReturningId(a, "私人状态");
        assertPersonalState(publicId, a, null, false);
        assertPersonalState(publicId, b, null, false);
        vote(publicId, a, "down");
        bookmark(publicId, a);
        vote(publicId, b, "up");
        assertPersonalState(publicId, a, "down", true);
        assertPersonalState(publicId, b, "up", false);
        mockMvc.perform(delete("/api/posts/{publicId}/votes", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(a)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/posts/{publicId}/bookmarks", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(a)).andExpect(status().isNoContent());
        assertPersonalState(publicId, a, null, false);
        assertPersonalState(publicId, b, "up", false);
        mockMvc.perform(get("/api/posts/{publicId}", publicId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.upVoteCount").value(1));
        mockMvc.perform(delete("/api/posts/{publicId}/votes", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(b)).andExpect(status().isNoContent());
        assertPersonalState(publicId, b, null, false);
        mockMvc.perform(get("/api/posts/{publicId}", publicId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.upVoteCount").value(0));
    }

    @Test
    @DisplayName("个人状态未登记为公开：无效或无 cookie 对公开、草稿、删除及不存在均先返回 401")
    void personalStateAuthenticatesBeforeVisibility() throws Exception {
        Cookie author = authCookie("认证作者");
        Cookie other = authCookie("认证其它账户");
        String published = createPostReturningId(author, "公开状态");
        String draft = createPostReturningId(author, "草稿状态");
        String deleted = createPostReturningId(author, "删除状态");
        mockMvc.perform(put("/api/posts/{publicId}", draft).header(HttpHeaders.ORIGIN, ORIGIN).cookie(author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PostCreateRequest("草稿", "内容", null, List.of(), "draft"))))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/posts/{publicId}", deleted).header(HttpHeaders.ORIGIN, ORIGIN).cookie(author))
                .andExpect(status().isNoContent());
        for (String publicId : List.of(published, draft, deleted, com.luyi.myfirst.common.id.Ulid.generate())) {
            mockMvc.perform(get("/api/posts/{publicId}/interactions/me", publicId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer ignored"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
            mockMvc.perform(get("/api/posts/{publicId}/interactions/me", publicId)
                            .cookie(new Cookie(AuthCookie.NAME, "invalid")))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        assertPersonalState(draft, author, null, false);
        for (String publicId : List.of(draft, deleted, com.luyi.myfirst.common.id.Ulid.generate())) {
            mockMvc.perform(get("/api/posts/{publicId}/interactions/me", publicId).cookie(other))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
        }
        mockMvc.perform(get("/api/posts/{publicId}/interactions/me", deleted).cookie(author))
                .andExpect(status().isNotFound());
        assertThat(com.luyi.myfirst.web.auth.interceptor.AuthInterceptor.isPublicRoute(
                "GET", "/api/posts/" + published + "/interactions/me")).isFalse();
        assertThat(com.luyi.myfirst.web.auth.interceptor.AuthInterceptor.isPublicRoute(
                "GET", "/api/posts/" + published + "/interactions/unregistered")).isFalse();
    }

    @Test
    @DisplayName("回复列表的父标识在同页、跨页、根删除与历史深层关系中保持正确")
    void replyListJsonResolvesParents() throws Exception {
        Cookie author = authCookie("回复作者");
        String publicId = createPostReturningId(author, "回复列表");
        String parent = createCommentReturningId(publicId, author, "父正文");
        String reply = createReplyReturningId(publicId, author, "回复正文", parent);
        mockMvc.perform(get("/api/posts/{publicId}/comments", publicId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].publicId").value(reply))
                .andExpect(jsonPath("$.items[0].parentPublicId").value(parent))
                .andExpect(jsonPath("$.items[1].parentPublicId").value(org.hamcrest.Matchers.nullValue()));
        JsonNode first = readCommentPage(publicId, null, 1);
        assertThat(first.get("items").get(0).get("parentPublicId").asText()).isEqualTo(parent);
        JsonNode second = readCommentPage(publicId, first.get("nextCursor").asText(), 1);
        assertThat(second.get("items").get(0).get("publicId").asText()).isEqualTo(parent);
        String deeper = createReplyReturningId(publicId, author, "历史深层", reply);
        mockMvc.perform(delete("/api/posts/{publicId}/comments/{commentId}", publicId, parent)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(author)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/posts/{publicId}/comments", publicId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].publicId").value(deeper))
                .andExpect(jsonPath("$.items[0].parentPublicId").value(reply))
                .andExpect(jsonPath("$.items[1].publicId").value(reply))
                .andExpect(jsonPath("$.items[1].parentPublicId").value(parent))
                .andExpect(jsonPath("$.items[*].content", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("父正文"))));
        String otherPost = createPostReturningId(author, "其它帖");
        String crossPost = createCommentReturningId(otherPost, author, "跨帖父");
        for (String target : List.of(parent, crossPost, com.luyi.myfirst.common.id.Ulid.generate())) {
            mockMvc.perform(post("/api/posts/{publicId}/comments", publicId)
                            .header(HttpHeaders.ORIGIN, ORIGIN).cookie(author).contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CommentCreateRequest("不应创建", target))))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
        }
        assertThat(readCommentPage(publicId, null, 20).get("items").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("评论 HTTP 默认 20、上限 50、非法游标 400，续页保持倒序至 nextCursor=null")
    void commentPaginationJsonContract() throws Exception {
        Cookie author = authCookie("分页作者");
        String publicId = createPostReturningId(author, "分页契约");
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 55; index++) {
            ids.add(createCommentReturningId(publicId, author, "评论" + index));
        }
        Collections.reverse(ids);
        JsonNode first = readCommentPage(publicId, null, null);
        assertThat(commentIds(first)).containsExactlyElementsOf(ids.subList(0, 20));
        JsonNode second = readCommentPage(publicId, first.get("nextCursor").asText(), null);
        assertThat(commentIds(second)).containsExactlyElementsOf(ids.subList(20, 40));
        JsonNode last = readCommentPage(publicId, second.get("nextCursor").asText(), null);
        assertThat(commentIds(last)).containsExactlyElementsOf(ids.subList(40, 55));
        assertThat(last.get("nextCursor").isNull()).isTrue();
        assertThat(readCommentPage(publicId, null, 500).get("items").size()).isEqualTo(50);
        mockMvc.perform(get("/api/posts/{publicId}/comments", publicId).param("cursor", "broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("评论 HTTP 保持 Java trim 与 UTF-16 边界，拒绝不落库")
    void commentUtf16JsonContract() throws Exception {
        Cookie author = authCookie("内容作者");
        String publicId = createPostReturningId(author, "UTF-16");
        String emoji = "😀".repeat(1000);
        mockMvc.perform(post("/api/posts/{publicId}/comments", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(author).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CommentCreateRequest("\u001f " + emoji + " \u0001", null))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.content").value(emoji));
        for (String invalid : new String[]{null, "\u001f ", emoji + "x"}) {
            mockMvc.perform(post("/api/posts/{publicId}/comments", publicId)
                            .header(HttpHeaders.ORIGIN, ORIGIN).cookie(author).contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CommentCreateRequest(invalid, null))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(readCommentPage(publicId, null, 20).get("items").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("所有互动 DELETE 都先来源后认证，取消本人记录不存在时仍区分 403 与 404")
    void deleteGuardsAndCancelErrorsRemainUnchanged() throws Exception {
        Cookie author = authCookie("取消作者");
        Cookie other = authCookie("取消其它账户");
        String publicId = createPostReturningId(author, "取消边界");
        for (String suffix : List.of("/comments/missing", "/votes", "/bookmarks")) {
            mockMvc.perform(delete("/api/posts/" + publicId + suffix))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));
            mockMvc.perform(delete("/api/posts/" + publicId + suffix).header(HttpHeaders.ORIGIN, ORIGIN))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        vote(publicId, author, "up");
        bookmark(publicId, author);
        for (String suffix : List.of("/votes", "/bookmarks")) {
            mockMvc.perform(delete("/api/posts/" + publicId + suffix).header(HttpHeaders.ORIGIN, ORIGIN).cookie(other))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("POST_FORBIDDEN"));
            mockMvc.perform(delete("/api/posts/" + publicId + suffix).header(HttpHeaders.ORIGIN, ORIGIN).cookie(author))
                    .andExpect(status().isNoContent());
            mockMvc.perform(delete("/api/posts/" + publicId + suffix).header(HttpHeaders.ORIGIN, ORIGIN).cookie(author))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
        }
    }

    // ---------- 夹具 ----------

    private void assertPersonalState(String publicId, Cookie viewer, String direction, boolean bookmarked) throws Exception {
        String json = mockMvc.perform(get("/api/posts/{publicId}/interactions/me", publicId).cookie(viewer)
                        .param("accountPublicId", "forged").param("accountId", "0"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();
        JsonNode state = objectMapper.readTree(json);
        Set<String> fields = new HashSet<>();
        state.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("voteDirection", "bookmarked");
        assertThat(state.get("voteDirection").isNull() ? null : state.get("voteDirection").asText()).isEqualTo(direction);
        assertThat(state.get("bookmarked").isBoolean()).isTrue();
        assertThat(state.get("bookmarked").asBoolean()).isEqualTo(bookmarked);
    }

    private JsonNode readCommentPage(String publicId, String cursor, Integer limit) throws Exception {
        var request = get("/api/posts/{publicId}/comments", publicId);
        if (cursor != null) { request.param("cursor", cursor); }
        if (limit != null) { request.param("limit", limit.toString()); }
        return objectMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private List<String> commentIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("publicId").asText()));
        return ids;
    }

    private String createReplyReturningId(String publicId, Cookie author, String content, String parent) throws Exception {
        String json = mockMvc.perform(post("/api/posts/{publicId}/comments", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(author).contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CommentCreateRequest(content, parent))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("publicId").asText();
    }

    private void assertPublicReadsStatus(String publicId, Cookie viewer, int expected) throws Exception {
        for (String suffix : List.of("", "/comments")) {
            var request = get("/api/posts/" + publicId + suffix);
            if (viewer != null) {
                request.cookie(viewer);
            }
            var result = mockMvc.perform(request).andExpect(status().is(expected));
            if (expected == 404) {
                result.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
            }
        }
    }

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
