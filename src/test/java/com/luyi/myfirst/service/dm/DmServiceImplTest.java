package com.luyi.myfirst.service.dm;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.exception.DmErrorEnum;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.dm.entity.DmConversation;
import com.luyi.myfirst.dao.dm.entity.DmMessage;
import com.luyi.myfirst.dao.dm.repository.DmConversationRepository;
import com.luyi.myfirst.dao.dm.repository.DmMessageRepository;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.dm.model.DmConversationSummary;
import com.luyi.myfirst.service.dm.model.DmMessagePage;
import com.luyi.myfirst.service.dm.model.DmMessageView;
import com.luyi.myfirst.service.dm.model.DmOpenResult;
import com.luyi.myfirst.service.dm.model.DmSendCommand;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 私信服务层的读写契约（spec 中可由服务层表达的部分）。
 *
 * 401 与「未登录」不在服务层：拦截器在 controller 之前就拒绝了请求。
 * SSE 推送也不在这里：它发生在事务提交之后，属于 web 层的编排。
 */
@SpringBootTest
@Transactional
class DmServiceImplTest {

    @Autowired
    private DmService service;

    @Autowired
    private DmConversationRepository conversations;

    @Autowired
    private DmMessageRepository messages;

    @Autowired
    private AccountRepository accounts;

    // ---------- 发起会话 ----------

    @Test
    @DisplayName("首次发起：created=true，计数 0，名片与对方一致")
    void firstOpenCreatesConversation() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");

        DmOpenResult result = service.open(a, b.account().publicId());

        assertThat(result.created()).isTrue();
        assertThat(result.conversation().publicId()).hasSize(26);
        assertThat(result.conversation().peer().publicId()).isEqualTo(b.account().publicId());
        assertThat(result.conversation().peer().displayName()).isEqualTo("乙");
        assertThat(result.conversation().messageCount()).isZero();
        assertThat(result.conversation().unreadCount()).isZero();
        assertThat(result.conversation().lastMessagePreview()).isNull();
        assertThat(result.conversation().lastMessageAt()).isNull();
    }

    @Test
    @DisplayName("重复发起：任一方发起都返回同一会话，created=false")
    void repeatedOpenReturnsSameConversation() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");

        String first = service.open(a, b.account().publicId()).conversation().publicId();

        DmOpenResult again = service.open(a, b.account().publicId());
        DmOpenResult reversed = service.open(b, a.account().publicId());

        assertThat(again.created()).isFalse();
        assertThat(again.conversation().publicId()).isEqualTo(first);
        assertThat(reversed.created()).isFalse();
        assertThat(reversed.conversation().publicId()).isEqualTo(first);
        assertThat(reversed.conversation().peer().publicId()).isEqualTo(a.account().publicId());
    }

    @Test
    @DisplayName("不能向自己发起：DM_SELF_CONVERSATION")
    void selfConversationRejected() {
        VerifiedToken a = newToken("甲");

        assertThat(rejectionOf(() -> service.open(a, a.account().publicId())).getErrorCode())
                .isEqualTo(DmErrorEnum.DM_SELF_CONVERSATION.getErrorCode());
    }

    @Test
    @DisplayName("对方不存在或已注销：DM_PEER_NOT_FOUND")
    void unknownPeerRejected() {
        VerifiedToken a = newToken("甲");
        VerifiedToken gone = newToken("已注销");
        gone.account().delete(Instant.now());
        accounts.saveAndFlush(gone.account());

        assertThat(rejectionOf(() -> service.open(a, Ulid.generate())).getErrorCode())
                .isEqualTo(DmErrorEnum.DM_PEER_NOT_FOUND.getErrorCode());
        assertThat(rejectionOf(() -> service.open(a, gone.account().publicId())).getErrorCode())
                .isEqualTo(DmErrorEnum.DM_PEER_NOT_FOUND.getErrorCode());
    }

    // ---------- 会话列表 ----------

    @Test
    @DisplayName("只返回本人参与的会话，按最近活跃倒序")
    void listOnlyMineOrderedByActivity() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        VerifiedToken c = newToken("丙");

        String ab = service.open(a, b.account().publicId()).conversation().publicId();
        sleep(10);
        String ac = service.open(a, c.account().publicId()).conversation().publicId();
        service.send(a, ab, new DmSendCommand("先给乙"));
        sleep(10);
        service.send(a, ac, new DmSendCommand("再给丙"));
        sleep(10);
        service.send(a, ab, new DmSendCommand("又给乙"));

        assertThat(service.conversations(a, 20).items())
                .extracting(DmConversationSummary::publicId)
                .containsExactly(ab, ac);
        assertThat(service.conversations(a, 20).items().get(0).lastMessagePreview()).isEqualTo("又给乙");

        assertThat(service.conversations(b, 20).items())
                .extracting(DmConversationSummary::publicId)
                .containsExactly(ab);
        assertThat(service.conversations(c, 20).items())
                .extracting(DmConversationSummary::publicId)
                .containsExactly(ac);
    }

    @Test
    @DisplayName("尚无消息的会话按创建时间参与同一排序")
    void emptyConversationOrderedByCreatedAt() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        VerifiedToken c = newToken("丙");

        String ab = service.open(a, b.account().publicId()).conversation().publicId();
        sleep(10);
        String ac = service.open(a, c.account().publicId()).conversation().publicId();

        assertThat(service.conversations(a, 20).items())
                .extracting(DmConversationSummary::publicId)
                .containsExactly(ac, ab);
    }

    @Test
    @DisplayName("没有会话时返回空列表")
    void emptyList() {
        VerifiedToken a = newToken("甲");

        assertThat(service.conversations(a, 20).items()).isEmpty();
        assertThat(service.unreadCount(a)).isZero();
    }

    @Test
    @DisplayName("会话列表 limit 超出 1–50 时钳制到边界")
    void conversationLimitClamped() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        VerifiedToken c = newToken("丙");
        service.open(a, b.account().publicId());
        service.open(a, c.account().publicId());

        assertThat(service.conversations(a, 0).items()).hasSize(1);
        assertThat(service.conversations(a, 1).items()).hasSize(1);
        assertThat(service.conversations(a, 500).items()).hasSize(2);
    }

    @Test
    @DisplayName("摘要超过 50 个码点时被截断")
    void previewTruncated() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        String ab = service.open(a, b.account().publicId()).conversation().publicId();

        service.send(a, ab, new DmSendCommand("字".repeat(60)));

        assertThat(service.conversations(a, 20).items().get(0).lastMessagePreview())
                .isEqualTo("字".repeat(50));
    }

    // ---------- 非参与者 ----------

    @Test
    @DisplayName("非参与者读、发、标记已读全部 404，且消息未被写入")
    void nonParticipantGetsNotFound() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        VerifiedToken c = newToken("丙");
        String ab = service.open(a, b.account().publicId()).conversation().publicId();

        assertThat(rejectionOf(() -> service.messages(c, ab, null, 20)).getErrorCode())
                .isEqualTo(DmErrorEnum.DM_CONVERSATION_NOT_FOUND.getErrorCode());
        assertThat(rejectionOf(() -> service.send(c, ab, new DmSendCommand("插话"))).getErrorCode())
                .isEqualTo(DmErrorEnum.DM_CONVERSATION_NOT_FOUND.getErrorCode());
        assertThat(rejectionOf(() -> service.markRead(c, ab)).getErrorCode())
                .isEqualTo(DmErrorEnum.DM_CONVERSATION_NOT_FOUND.getErrorCode());
        assertThat(service.messages(a, ab, null, 20).items()).isEmpty();
    }

    // ---------- 消息分页 ----------

    @Test
    @DisplayName("消息分页：首页倒序、游标不重复、到底 nextCursor 为 null")
    void messagePagination() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        String ab = service.open(a, b.account().publicId()).conversation().publicId();
        for (int i = 0; i < 5; i++) {
            service.send(a, ab, new DmSendCommand("第" + i + "条"));
        }

        DmMessagePage first = service.messages(a, ab, null, 2);
        assertThat(first.items()).extracting(DmMessageView::content)
                .containsExactly("第4条", "第3条");
        assertThat(first.nextCursor()).isNotNull();

        DmMessagePage second = service.messages(a, ab, first.nextCursor(), 2);
        assertThat(second.items()).extracting(DmMessageView::content)
                .containsExactly("第2条", "第1条");
        assertThat(second.nextCursor()).isNotNull();

        DmMessagePage third = service.messages(a, ab, second.nextCursor(), 2);
        assertThat(third.items()).extracting(DmMessageView::content)
                .containsExactly("第0条");
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    @DisplayName("消息分页 limit 钳制：0→1，500→50")
    void messageLimitClamped() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        String ab = service.open(a, b.account().publicId()).conversation().publicId();
        for (int i = 0; i < 3; i++) {
            service.send(a, ab, new DmSendCommand("第" + i + "条"));
        }

        assertThat(service.messages(a, ab, null, 0).items()).hasSize(1);
        assertThat(service.messages(a, ab, null, 500).items()).hasSize(3);
    }

    @Test
    @DisplayName("非法游标归 VALIDATION_FAILED")
    void brokenCursorRejected() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        String ab = service.open(a, b.account().publicId()).conversation().publicId();

        assertThat(rejectionOf(() -> service.messages(a, ab, "!!!bad!!!", 20)).getErrorCode())
                .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
    }

    // ---------- 正文校验 ----------

    @Test
    @DisplayName("空正文与纯空白被拒，消息数不变")
    void blankContentRejected() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        String ab = service.open(a, b.account().publicId()).conversation().publicId();

        assertThat(rejectionOf(() -> service.send(a, ab, new DmSendCommand(""))).getErrorCode())
                .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
        assertThat(rejectionOf(() -> service.send(a, ab, new DmSendCommand(" \n\t "))).getErrorCode())
                .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
        assertThat(rejectionOf(() -> service.send(a, ab, null)).getErrorCode())
                .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
        assertThat(service.conversations(a, 20).items().get(0).messageCount()).isZero();
    }

    @Test
    @DisplayName("2000 字符接受，2001 字符被拒")
    void contentLengthBoundary() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        String ab = service.open(a, b.account().publicId()).conversation().publicId();

        assertThat(service.send(a, ab, new DmSendCommand("a".repeat(2000))).content()).hasSize(2000);
        assertThat(rejectionOf(() -> service.send(a, ab, new DmSendCommand("a".repeat(2001)))).getErrorCode())
                .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
    }

    // ---------- 摘要与未读 ----------

    @Test
    @DisplayName("发送成功：消息在双方读取中都可见")
    void sentMessageVisibleToBoth() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        String ab = service.open(a, b.account().publicId()).conversation().publicId();

        DmMessageView sent = service.send(a, ab, new DmSendCommand("你好"));

        assertThat(sent.publicId()).hasSize(26);
        assertThat(sent.senderPublicId()).isEqualTo(a.account().publicId());
        assertThat(sent.content()).isEqualTo("你好");
        assertThat(sent.createdAt()).isNotNull();
        assertThat(service.messages(a, ab, null, 20).items()).hasSize(1);
        assertThat(service.messages(b, ab, null, 20).items()).hasSize(1);
    }

    @Test
    @DisplayName("收到消息未读增加，自己的消息不计未读")
    void unreadCounting() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        String ab = service.open(a, b.account().publicId()).conversation().publicId();

        service.send(b, ab, new DmSendCommand("一"));
        service.send(b, ab, new DmSendCommand("二"));

        assertThat(service.conversations(a, 20).items().get(0).unreadCount()).isEqualTo(2);
        assertThat(service.conversations(b, 20).items().get(0).unreadCount()).isZero();
        assertThat(service.unreadCount(a)).isEqualTo(2);
        assertThat(service.unreadCount(b)).isZero();
    }

    @Test
    @DisplayName("标记已读归零，不影响对方，重复调用幂等")
    void markReadClearsAndIsIdempotent() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        String ab = service.open(a, b.account().publicId()).conversation().publicId();
        service.send(b, ab, new DmSendCommand("一"));
        service.send(b, ab, new DmSendCommand("二"));

        DmConversationSummary read = service.markRead(a, ab);

        assertThat(read.unreadCount()).isZero();
        assertThat(service.unreadCount(a)).isZero();
        assertThat(service.conversations(b, 20).items().get(0).unreadCount()).isZero();
        assertThat(service.markRead(a, ab).unreadCount()).isZero();
    }

    @Test
    @DisplayName("没有消息时标记已读不报错")
    void markReadOnEmptyConversation() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        String ab = service.open(a, b.account().publicId()).conversation().publicId();

        assertThat(service.markRead(a, ab).unreadCount()).isZero();
    }

    @Test
    @DisplayName("标记已读按位置重算：已读到第 5 条时第 6 条不会被抹掉")
    void markReadRecomputesByPosition() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        DmConversation conversation = conversationOf(a, b);
        Long aId = a.account().id();

        for (int i = 0; i < 6; i++) {
            service.send(b, conversation.publicId(), new DmSendCommand("第" + i + "条"));
        }
        List<DmMessage> all = messages.findLatest(conversation.id(), PageRequest.of(0, 10));
        // 列表是倒序：index 0 是最新一条（第 6 条），index 1 是它前面那条（第 5 条）。
        Long upToFifth = all.get(1).id();

        // 模拟「已读推进到第 5 条时，第 6 条已经在库里」：按位置重算只抹掉前 5 条，
        // 第 6 条仍计为未读；盲清零会把它一起抹掉。
        if (conversation.participantLowId().equals(aId)) {
            conversations.markReadForLow(conversation.id(), aId, upToFifth);
        } else {
            conversations.markReadForHigh(conversation.id(), aId, upToFifth);
        }

        assertThat(conversations.findById(conversation.id()).orElseThrow().unreadCountOf(aId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("总未读等于各会话请求者一侧计数之和")
    void unreadCountSums() {
        VerifiedToken a = newToken("甲");
        VerifiedToken b = newToken("乙");
        VerifiedToken c = newToken("丙");
        String ab = service.open(a, b.account().publicId()).conversation().publicId();
        String ac = service.open(a, c.account().publicId()).conversation().publicId();
        service.send(b, ab, new DmSendCommand("一"));
        service.send(b, ab, new DmSendCommand("二"));
        service.send(c, ac, new DmSendCommand("一"));

        assertThat(service.unreadCount(a)).isEqualTo(3);
    }

    // ---------- 夹具 ----------

    private VerifiedToken newToken(String displayName) {
        String publicId = Ulid.generate();
        Account account = accounts.saveAndFlush(Account.createActive(
                publicId,
                publicId.toLowerCase() + "@example.com",
                "hash-not-used",
                displayName));
        return new VerifiedToken(account, "jti-" + publicId);
    }

    private DmConversation conversationOf(VerifiedToken a, VerifiedToken b) {
        String publicId = service.open(a, b.account().publicId()).conversation().publicId();
        return conversations.findByPublicIdAndDeletedAtIsNull(publicId).orElseThrow();
    }

    private static BizException rejectionOf(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BizException.class);
        return (BizException) thrown;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
