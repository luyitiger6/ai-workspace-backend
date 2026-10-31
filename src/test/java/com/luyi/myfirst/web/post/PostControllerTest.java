package com.luyi.myfirst.web.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.PostStatus;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.web.ApiResponse;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.entity.AuthSession;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import com.luyi.myfirst.service.auth.model.IssuedToken;
import com.luyi.myfirst.service.auth.support.TokenIssuer;
import com.luyi.myfirst.web.auth.AuthCookie;
import com.luyi.myfirst.web.post.dto.PostCreateRequest;
import com.luyi.myfirst.web.post.dto.PostResponse;
import com.luyi.myfirst.web.post.dto.PostSummaryResponse;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 帖子端点的 HTTP 契约（spec R2/R4/R5/R6/R7/R8 中需 MockMvc 表达的部分）。
 *
 * 这里不重复 service 层的边界：service 测试已经钉过标题长度、游标翻页等；
 * web 层的职责是「身份从 cookie 来、来源从 Origin 头来、DTO 映射正确、状态码对」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostControllerTest {

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

    // ---------- 5.1 五个端点与类型标记 ----------

    @Test
    @DisplayName("PostResponse 实现 ApiResponse 标记接口")
    void postResponseImplementsApiResponse() {
        assertThat(PostResponse.class.getInterfaces()).contains(ApiResponse.class);
        assertThat(PostSummaryResponse.class.getInterfaces()).contains(ApiResponse.class);
    }

    @Test
    @DisplayName("发布：同源 + 有效令牌 → 201，响应含白名单字段")
    void createPost() throws Exception {
        Cookie cookie = authCookie("作者甲");

        mockMvc.perform(post("/api/posts")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PostCreateRequest("  成都三日  ", "正文", null, List.of(), "published"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.publicId").isString())
                .andExpect(jsonPath("$.title").value("成都三日"))
                .andExpect(jsonPath("$.status").value("published"))
                .andExpect(jsonPath("$.authorPublicId").isString())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.commentCount").value(0))
                .andExpect(jsonPath("$.upVoteCount").value(0))
                .andExpect(jsonPath("$.bookmarkCount").value(0));
    }

    @Test
    @DisplayName("编辑与删除：状态码分别为 200 与 204")
    void updateAndDeletePost() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String publicId = createPostReturningId(cookie, "原题");

        mockMvc.perform(put("/api/posts/{publicId}", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PostCreateRequest("新题", "新正文", null, List.of(), "draft"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新题"));

        mockMvc.perform(delete("/api/posts/{publicId}", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("详情与列表：GET 公开，不带令牌也能访问")
    void readAndFeedArePublic() throws Exception {
        String publicId = createPostReturningId("公开");

        mockMvc.perform(get("/api/posts/{publicId}", publicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(publicId));

        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    @DisplayName("列表支持 sortBy 查询参数并返回统计字段")
    void feedWithSortByAndStats() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String publicId = createPostReturningId(cookie, "排序测试");

        mockMvc.perform(get("/api/posts?sortBy=upVoteCount"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.publicId=='" + publicId + "')].upVoteCount").value(0))
                .andExpect(jsonPath("$.items[?(@.publicId=='" + publicId + "')].commentCount").value(0))
                .andExpect(jsonPath("$.items[?(@.publicId=='" + publicId + "')].bookmarkCount").value(0));
    }

    @Test
    @DisplayName("非法 sortBy：400 VALIDATION_FAILED")
    void invalidSortByIsRejected() throws Exception {
        mockMvc.perform(get("/api/posts?sortBy=hot"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- 5.2 字段白名单 ----------

    @Test
    @DisplayName("响应字段集合等于白名单，且不含内部字段")
    void responseFieldsMatchWhitelist() throws Exception {
        Set<String> detailWhitelist = Set.of(
                "publicId", "title", "content", "coverImage", "tags",
                "status", "authorPublicId", "createdAt", "updatedAt",
                "commentCount", "upVoteCount", "bookmarkCount");
        Set<String> summaryWhitelist = Set.of(
                "publicId", "title", "coverImage", "tags", "status", "authorPublicId",
                "createdAt", "updatedAt", "commentCount", "upVoteCount", "bookmarkCount");
        Cookie cookie = authCookie("白名单作者");
        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/posts")
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PostCreateRequest("白名单", "正文", null, List.of(), "published"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String publicId = created.get("publicId").asText();
        JsonNode edited = objectMapper.readTree(mockMvc.perform(put("/api/posts/{publicId}", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PostCreateRequest("编辑白名单", "正文", null, List.of(), "published"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode read = objectMapper.readTree(mockMvc.perform(get("/api/posts/{publicId}", publicId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode feed = objectMapper.readTree(mockMvc.perform(get("/api/posts"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Set<String> detail = fieldNames(read);
        Set<String> summary = fieldNames(feed.get("items").get(0));

        assertThat(fieldNames(created)).containsExactlyInAnyOrderElementsOf(detailWhitelist);
        assertThat(fieldNames(edited)).containsExactlyInAnyOrderElementsOf(detailWhitelist);
        for (JsonNode response : List.of(created, edited, read)) {
            assertThat(response.get("commentCount").asLong()).isZero();
            assertThat(response.get("upVoteCount").asLong()).isZero();
            assertThat(response.get("bookmarkCount").asLong()).isZero();
        }
        assertThat(detail).containsExactlyInAnyOrderElementsOf(detailWhitelist);
        assertThat(summary).containsExactlyInAnyOrderElementsOf(summaryWhitelist);
        assertThat(summary).doesNotContain("content");
        assertThat(detail).doesNotContain(
                "id", "deletedAt", "version", "createdBy", "updatedBy", "publishedAt");
        assertThat(summary).doesNotContain(
                "id", "deletedAt", "version", "createdBy", "updatedBy", "publishedAt");
    }

    @Test
    @DisplayName("详情 HTTP 返回有效互动统计，反对及软删除不计入赞成数")
    void detailJsonCarriesEffectiveCounts() throws Exception {
        Cookie author = authCookie("统计作者");
        Cookie other = authCookie("统计参与者");
        String publicId = createPostReturningId(author, "详情统计");
        String commentJson = mockMvc.perform(post("/api/posts/{publicId}/comments", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(author)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"评论\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/api/posts/{publicId}/votes", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(author)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"up\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/posts/{publicId}/votes", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(other)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"down\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/posts/{publicId}/bookmarks", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(author))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/posts/{publicId}", publicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentCount").value(1))
                .andExpect(jsonPath("$.upVoteCount").value(1))
                .andExpect(jsonPath("$.bookmarkCount").value(1))
                .andExpect(jsonPath("$.voteDirection").doesNotExist())
                .andExpect(jsonPath("$.bookmarked").doesNotExist());
        mockMvc.perform(delete("/api/posts/{publicId}/comments/{commentId}", publicId,
                        objectMapper.readTree(commentJson).get("publicId").asText())
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(author))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/posts/{publicId}/votes", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(author))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/posts/{publicId}/bookmarks", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN).cookie(author))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/posts/{publicId}", publicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentCount").value(0))
                .andExpect(jsonPath("$.upVoteCount").value(0))
                .andExpect(jsonPath("$.bookmarkCount").value(0));
    }

    // ---------- 5.3 来源校验先于认证 ----------

    @Test
    @DisplayName("跨站写请求且缺令牌：403 CROSS_SITE_REJECTED，不是 401")
    void crossSiteWriteWithoutTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PostCreateRequest("x", "y", null, List.of(), "draft"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));
    }

    @Test
    @DisplayName("同源写请求但缺令牌：401 UNAUTHENTICATED")
    void sameSiteWriteWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PostCreateRequest("x", "y", null, List.of(), "draft"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- R2 补充：未登录发帖被拒、请求体不得指定作者 ----------

    @Test
    @DisplayName("未登录发帖被拒：401，不建帖子")
    void unauthenticatedPostIsRejected() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new PostCreateRequest("x", "y", null, List.of(), "draft"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("请求体里的 authorPublicId 被忽略，作者仍是令牌账户")
    void requestBodyAuthorFieldIsIgnored() throws Exception {
        Cookie cookie = authCookie("作者甲");

        mockMvc.perform(post("/api/posts")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"c\",\"tags\":[],\"status\":\"draft\",\"authorPublicId\":\"EVIL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorPublicId").value(authorOf(cookie)));
    }

    // ---------- R4 补充：偏移量参数不被接受 ----------

    @Test
    @DisplayName("列表带 offset 参数：被忽略，仍返回第一页")
    void offsetParameterIsIgnored() throws Exception {
        Cookie cookie = authCookie("作者甲");
        String publicId = createPostReturningId(cookie, "第一页");

        mockMvc.perform(get("/api/posts?offset=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].publicId").value(publicId));
    }

    // ---------- 夹具 ----------

    private String createPostReturningId(String title) throws Exception {
        return createPostReturningId(authCookie("作者甲"), title);
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

    private Cookie authCookie(String displayName) {
        String publicId = Ulid.generate();
        Account account = accounts.saveAndFlush(Account.createActive(
                publicId,
                publicId.toLowerCase() + "@example.com",
                "$2a$10$abcdefghijklmnopqrstuv",
                displayName));
        IssuedToken issued = issuer.issue(account, Instant.now());
        sessions.save(AuthSession.open(account.id(), issued.jti(), issued.expiresAt()));
        return new Cookie(AuthCookie.NAME, issued.value());
    }

    private String authorOf(Cookie cookie) {
        // 只取 publicId 部分：cookie 值是令牌，这里无法直接反解；夹具保证同一个账户。
        return accounts.findAll().stream()
                .filter(a -> a.displayName().equals("作者甲"))
                .findFirst()
                .map(Account::publicId)
                .orElseThrow();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static Set<String> fieldNames(JsonNode response) {
        Set<String> names = new HashSet<>();
        response.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
