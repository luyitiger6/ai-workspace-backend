package com.luyi.myfirst.web.dm;

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
import com.luyi.myfirst.web.dm.dto.CreateConversationRequest;
import com.luyi.myfirst.web.dm.dto.SendMessageRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 私信端点的 HTTP 契约（spec 中需 MockMvc 表达的部分）。
 *
 * 这里只钉 web 层能表达的事：身份从 cookie 来、状态码、字段白名单、错误码。
 * 分页、摘要截断、未读重算等语义已由 {@code DmServiceImplTest} 钉过，不重复。
 *
 * SSE 的「收到推送」不在这里：MockMvc 对长连接只能断言进入了异步模式，
 * 推送与断连清理由 {@link DmEventBrokerTest} 直接验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DmControllerTest {

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

    // ---------- 4.2 全部端点需要登录 ----------

    @Test
    @DisplayName("七个端点未带凭证一律 401 UNAUTHENTICATED")
    void allEndpointsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/dm/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/api/dm/conversations")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateConversationRequest(Ulid.generate()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/api/dm/conversations/{publicId}/messages", Ulid.generate()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/dm/conversations/{publicId}/messages", Ulid.generate())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SendMessageRequest("你好"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/dm/conversations/{publicId}/read", Ulid.generate())
                        .header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/dm/unread-count"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/dm/conversations/{publicId}/stream", Ulid.generate()))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 4.3 发起会话 ----------

    @Test
    @DisplayName("首次发起 201，重复发起 200 且返回同一会话")
    void createIs201Then200() throws Exception {
        Session a = newSession("甲");
        Session b = newSession("乙");

        MvcResult first = mockMvc.perform(post("/api/dm/conversations")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateConversationRequest(b.publicId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.publicId").isString())
                .andExpect(jsonPath("$.peer.publicId").value(b.publicId()))
                .andExpect(jsonPath("$.peer.displayName").value("乙"))
                .andExpect(jsonPath("$.messageCount").value(0))
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.lastMessagePreview").value((Object) null))
                .andExpect(jsonPath("$.lastMessageAt").value((Object) null))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn();

        String publicId = read(first).get("publicId").asText();

        mockMvc.perform(post("/api/dm/conversations")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateConversationRequest(b.publicId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(publicId));
    }

    @Test
    @DisplayName("向自己发起：400 DM_SELF_CONVERSATION")
    void selfConversationIs400() throws Exception {
        Session a = newSession("甲");

        mockMvc.perform(post("/api/dm/conversations")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateConversationRequest(a.publicId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DM_SELF_CONVERSATION"));
    }

    @Test
    @DisplayName("对方不存在：404 DM_PEER_NOT_FOUND；空 body 是 400 而不是 404")
    void unknownPeerAndEmptyBody() throws Exception {
        Session a = newSession("甲");

        mockMvc.perform(post("/api/dm/conversations")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateConversationRequest(Ulid.generate()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DM_PEER_NOT_FOUND"));

        mockMvc.perform(post("/api/dm/conversations")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- 4.3 / 4.4 非参与者 ----------

    @Test
    @DisplayName("非参与者：读消息 / 发消息 / 标记已读 / 订阅推送全部 404")
    void nonParticipantIs404Everywhere() throws Exception {
        Session a = newSession("甲");
        Session b = newSession("乙");
        Session c = newSession("丙");
        String publicId = open(a, b);

        mockMvc.perform(get("/api/dm/conversations/{publicId}/messages", publicId)
                        .cookie(c.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DM_CONVERSATION_NOT_FOUND"));

        mockMvc.perform(post("/api/dm/conversations/{publicId}/messages", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(c.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SendMessageRequest("插话"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DM_CONVERSATION_NOT_FOUND"));

        mockMvc.perform(post("/api/dm/conversations/{publicId}/read", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(c.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DM_CONVERSATION_NOT_FOUND"));

        mockMvc.perform(get("/api/dm/conversations/{publicId}/stream", publicId)
                        .cookie(c.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DM_CONVERSATION_NOT_FOUND"));

        // 三次被拒之后会话里仍然没有消息。
        mockMvc.perform(get("/api/dm/conversations/{publicId}/messages", publicId)
                        .cookie(a.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
    }

    // ---------- 4.3 发送消息 ----------

    @Test
    @DisplayName("正常发送：201 且响应恰含四个字段")
    void sendReturns201WithFourFields() throws Exception {
        Session a = newSession("甲");
        Session b = newSession("乙");
        String publicId = open(a, b);

        MvcResult result = mockMvc.perform(post("/api/dm/conversations/{publicId}/messages", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SendMessageRequest("你好"))))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = read(result);
        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "publicId", "senderPublicId", "content", "createdAt");
        assertThat(body.get("senderPublicId").asText()).isEqualTo(a.publicId());
        assertThat(body.get("content").asText()).isEqualTo("你好");
    }

    @Test
    @DisplayName("空正文与纯空白 400，超长 400，消息数保持不变")
    void invalidContentIs400AndWritesNothing() throws Exception {
        Session a = newSession("甲");
        Session b = newSession("乙");
        String publicId = open(a, b);

        expectSendRejected(a, publicId, "");
        expectSendRejected(a, publicId, " \n\t ");
        expectSendRejected(a, publicId, "a".repeat(2001));

        mockMvc.perform(get("/api/dm/conversations").cookie(a.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].messageCount").value(0));
    }

    @Test
    @DisplayName("2000 字符边界被接受")
    void lengthBoundaryIsAccepted() throws Exception {
        Session a = newSession("甲");
        Session b = newSession("乙");
        String publicId = open(a, b);

        mockMvc.perform(post("/api/dm/conversations/{publicId}/messages", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SendMessageRequest("a".repeat(2000)))))
                .andExpect(status().isCreated());
    }

    // ---------- 4.4 SSE ----------

    @Test
    @DisplayName("参与者订阅推送：进入异步模式")
    void participantSubscribes() throws Exception {
        Session a = newSession("甲");
        Session b = newSession("乙");
        String publicId = open(a, b);

        mockMvc.perform(get("/api/dm/conversations/{publicId}/stream", publicId)
                        .cookie(a.cookie()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    // ---------- 夹具 ----------

    private record Session(Cookie cookie, String publicId) {
    }

    private Session newSession(String displayName) {
        String publicId = Ulid.generate();
        Account account = accounts.saveAndFlush(Account.createActive(
                publicId,
                publicId.toLowerCase() + "@example.com",
                "hash-not-used",
                displayName));
        IssuedToken issued = issuer.issue(account, Instant.now());
        sessions.save(AuthSession.open(account.id(), issued.jti(), issued.expiresAt()));
        return new Session(new Cookie(AuthCookie.NAME, issued.value()), publicId);
    }

    private String open(Session owner, Session peer) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/dm/conversations")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateConversationRequest(peer.publicId()))))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).get("publicId").asText();
    }

    private void expectSendRejected(Session sender, String publicId, String content) throws Exception {
        mockMvc.perform(post("/api/dm/conversations/{publicId}/messages", publicId)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(sender.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new SendMessageRequest(content))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
