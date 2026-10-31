package com.luyi.myfirst.service.post.interaction;

import com.luyi.myfirst.common.enums.PostStatus;
import com.luyi.myfirst.common.enums.VoteDirection;
import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.exception.PostErrorEnum;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.post.PostService;
import com.luyi.myfirst.service.post.interaction.model.BookmarkResult;
import com.luyi.myfirst.service.post.interaction.model.CommentCreateCommand;
import com.luyi.myfirst.service.post.interaction.model.CommentPage;
import com.luyi.myfirst.service.post.interaction.model.CommentView;
import com.luyi.myfirst.service.post.interaction.model.VoteCreateCommand;
import com.luyi.myfirst.service.post.interaction.model.VoteResult;
import com.luyi.myfirst.service.post.model.PostCommand;
import com.luyi.myfirst.service.post.model.PostPage;
import com.luyi.myfirst.service.post.model.PostSummary;
import com.luyi.myfirst.service.post.model.PostView;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 互动服务层的读写契约（spec 中可由 service 接口表达的部分）。
 *
 * 401、403、来源校验发生在拦截器与 MockMvc 之间，不在这里重复；
 * 这里只钉业务状态：创建/删除/替换后数据行与帖子统计的变化。
 */
@SpringBootTest
@Transactional
class PostInteractionServiceTest {

    @Autowired
    private PostInteractionService interactions;

    @Autowired
    private PostService posts;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private EntityManager entityManager;

    // ---------- 评论：创建、删除、列表、二级回复 ----------

    @Test
    @DisplayName("登录用户发表评论：作者取自令牌，帖子评论数加 1")
    void createCommentBindsAuthorAndIncreasesCount() {
        VerifiedToken author = newToken("作者甲");
        PostView post = posts.publish(author, published("成都三日"));

        CommentView comment = interactions.createComment(author, post.publicId(),
                new CommentCreateCommand("  写得好  ", null));

        assertThat(comment.publicId()).hasSize(26);
        assertThat(comment.postPublicId()).isEqualTo(post.publicId());
        assertThat(comment.authorPublicId()).isEqualTo(author.account().publicId());
        assertThat(comment.content()).isEqualTo("写得好");
        assertThat(comment.parentPublicId()).isNull();
        assertThat(comment.createdAt()).isNotNull();

        PostSummary summary = feedOne(post.publicId());
        assertThat(summary.commentCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("回复评论：parentPublicId 携带并返回")
    void replyCommentCarriesParentId() {
        VerifiedToken author = newToken("作者甲");
        PostView post = posts.publish(author, published("成都三日"));
        CommentView parent = interactions.createComment(author, post.publicId(),
                new CommentCreateCommand("顶层", null));

        CommentView reply = interactions.createComment(author, post.publicId(),
                new CommentCreateCommand("回复", parent.publicId()));

        assertThat(reply.parentPublicId()).isEqualTo(parent.publicId());
    }

    @Test
    @DisplayName("空或超长评论内容：400 VALIDATION_FAILED，不写库")
    void invalidCommentContentIsRejected() {
        VerifiedToken author = newToken("作者甲");
        PostView post = posts.publish(author, published("成都三日"));

        assertThat(rejectionOf(() -> interactions.createComment(author, post.publicId(),
                new CommentCreateCommand("   ", null))).getErrorCode())
                .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());

        assertThat(rejectionOf(() -> interactions.createComment(author, post.publicId(),
                new CommentCreateCommand("x".repeat(2001), null))).getErrorCode())
                .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());

        assertThat(interactions.listComments(post.publicId(), null, null, 10).items()).isEmpty();
    }

    @Test
    @DisplayName("作者删除评论：软删除后列表不可见，帖子评论数减 1")
    void authorDeletesOwnComment() {
        VerifiedToken author = newToken("作者甲");
        PostView post = posts.publish(author, published("成都三日"));
        CommentView comment = interactions.createComment(author, post.publicId(),
                new CommentCreateCommand("将被删除", null));

        interactions.deleteComment(author, post.publicId(), comment.publicId());

        assertThat(interactions.listComments(post.publicId(), null, null, 10).items())
                .extracting(CommentView::publicId)
                .doesNotContain(comment.publicId());
        assertThat(feedOne(post.publicId()).commentCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("非作者删除评论：403 POST_FORBIDDEN，数据不变")
    void nonAuthorCannotDeleteComment() {
        VerifiedToken author = newToken("作者甲");
        VerifiedToken other = newToken("路人乙");
        PostView post = posts.publish(author, published("成都三日"));
        CommentView comment = interactions.createComment(author, post.publicId(),
                new CommentCreateCommand("留着", null));

        BizException error = rejectionOf(() -> interactions.deleteComment(other, post.publicId(), comment.publicId()));

        assertThat(error.getErrorCode()).isEqualTo(PostErrorEnum.POST_FORBIDDEN.getErrorCode());
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(interactions.listComments(post.publicId(), null, null, 10).items())
                .extracting(CommentView::publicId)
                .contains(comment.publicId());
    }

    @Test
    @DisplayName("评论列表按创建时间倒序，支持游标分页")
    void listCommentsOrderedAndPaged() {
        VerifiedToken author = newToken("作者甲");
        PostView post = posts.publish(author, published("成都三日"));
        CommentView first = interactions.createComment(author, post.publicId(),
                new CommentCreateCommand("第一条", null));
        CommentView second = interactions.createComment(author, post.publicId(),
                new CommentCreateCommand("第二条", null));

        CommentPage page = interactions.listComments(post.publicId(), null, null, 1);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).publicId()).isEqualTo(second.publicId());
        assertThat(page.nextCursor()).isNotNull();

        CommentPage next = interactions.listComments(post.publicId(), null, page.nextCursor(), 1);
        assertThat(next.items()).hasSize(1);
        assertThat(next.items().get(0).publicId()).isEqualTo(first.publicId());
        assertThat(next.nextCursor()).isNull();
    }

    @Test
    @DisplayName("评论读取共享详情可见性：已发布公开，草稿仅作者，删除与不存在均 404")
    void commentReadVisibilityMatchesDetail() {
        VerifiedToken author = newToken("草稿作者");
        VerifiedToken other = newToken("其它账户");
        PostView post = posts.publish(author, published("公开评论"));
        interactions.createComment(author, post.publicId(), new CommentCreateCommand("正文评论", null));
        for (VerifiedToken viewer : new VerifiedToken[]{null, author, other}) {
            assertThat(interactions.listComments(post.publicId(), viewer, null, 20).items())
                    .extracting(CommentView::content).containsExactly("正文评论");
        }
        posts.edit(author, post.publicId(), new PostCommand("草稿", "内容", null, List.of(), PostStatus.DRAFT));
        assertThat(interactions.listComments(post.publicId(), author, null, 20).items()).hasSize(1);
        for (VerifiedToken viewer : new VerifiedToken[]{null, other}) {
            assertNotFound(() -> interactions.listComments(post.publicId(), viewer, null, 20));
            assertNotFound(() -> posts.read(post.publicId(), viewer));
        }
        // 本 CHANGE 只修改读取边界：其它账户的既有草稿写行为保持不变。
        interactions.createComment(other, post.publicId(), new CommentCreateCommand("既有写规则", null));
        assertThat(interactions.listComments(post.publicId(), author, null, 20).items()).hasSize(2);
        posts.delete(author, post.publicId());
        for (VerifiedToken viewer : new VerifiedToken[]{null, author, other}) {
            assertNotFound(() -> interactions.listComments(post.publicId(), viewer, null, 20));
            assertNotFound(() -> interactions.listComments(Ulid.generate(), viewer, null, 20));
        }
    }

    // ---------- 投票：创建、替换、删除 ----------

    @Test
    @DisplayName("首次投赞成票：201 语义（created=true），帖子 upVoteCount 加 1")
    void firstUpVoteIncreasesCount() {
        VerifiedToken author = newToken("作者甲");
        PostView post = posts.publish(author, published("成都三日"));

        VoteResult result = interactions.vote(author, post.publicId(), new VoteCreateCommand(VoteDirection.UP));

        assertThat(result.created()).isTrue();
        assertThat(result.view().direction()).isEqualTo(VoteDirection.UP);
        assertThat(feedOne(post.publicId()).upVoteCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("重复投票替换方向：200 语义，upVoteCount 随之变化")
    void repeatedVoteReplacesDirection() {
        VerifiedToken author = newToken("作者甲");
        PostView post = posts.publish(author, published("成都三日"));
        interactions.vote(author, post.publicId(), new VoteCreateCommand(VoteDirection.UP));

        VoteResult second = interactions.vote(author, post.publicId(), new VoteCreateCommand(VoteDirection.DOWN));

        assertThat(second.created()).isFalse();
        assertThat(second.view().direction()).isEqualTo(VoteDirection.DOWN);
        assertThat(feedOne(post.publicId()).upVoteCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("投票人取消投票：204 语义，upVoteCount 减 1")
    void voterCancelsVote() {
        VerifiedToken author = newToken("作者甲");
        PostView post = posts.publish(author, published("成都三日"));
        interactions.vote(author, post.publicId(), new VoteCreateCommand(VoteDirection.UP));

        interactions.cancelVote(author, post.publicId());

        assertThat(feedOne(post.publicId()).upVoteCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("非投票人取消投票：403 POST_FORBIDDEN，计数不变")
    void nonVoterCannotCancelVote() {
        VerifiedToken author = newToken("作者甲");
        VerifiedToken other = newToken("路人乙");
        PostView post = posts.publish(author, published("成都三日"));
        interactions.vote(author, post.publicId(), new VoteCreateCommand(VoteDirection.UP));

        BizException error = rejectionOf(() -> interactions.cancelVote(other, post.publicId()));

        assertThat(error.getErrorCode()).isEqualTo(PostErrorEnum.POST_FORBIDDEN.getErrorCode());
        assertThat(feedOne(post.publicId()).upVoteCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("服务层拒绝空方向：400 VALIDATION_FAILED")
    void nullVoteDirectionIsRejected() {
        VerifiedToken author = newToken("作者甲");
        PostView post = posts.publish(author, published("成都三日"));

        BizException error = rejectionOf(() -> interactions.vote(author, post.publicId(), new VoteCreateCommand(null)));

        assertThat(error.getErrorCode()).isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
    }

    // ---------- 收藏：创建、幂等、删除 ----------

    @Test
    @DisplayName("首次收藏：201 语义，帖子 bookmarkCount 加 1")
    void firstBookmarkIncreasesCount() {
        VerifiedToken author = newToken("作者甲");
        PostView post = posts.publish(author, published("成都三日"));

        BookmarkResult result = interactions.bookmark(author, post.publicId());

        assertThat(result.created()).isTrue();
        assertThat(result.view().postPublicId()).isEqualTo(post.publicId());
        assertThat(feedOne(post.publicId()).bookmarkCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("重复收藏幂等：200 语义，bookmarkCount 不重复增加")
    void repeatedBookmarkIsIdempotent() {
        VerifiedToken author = newToken("作者甲");
        PostView post = posts.publish(author, published("成都三日"));
        interactions.bookmark(author, post.publicId());

        BookmarkResult result = interactions.bookmark(author, post.publicId());

        assertThat(result.created()).isFalse();
        assertThat(feedOne(post.publicId()).bookmarkCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("收藏人取消收藏：204 语义，bookmarkCount 减 1")
    void bookmarkerCancelsBookmark() {
        VerifiedToken author = newToken("作者甲");
        PostView post = posts.publish(author, published("成都三日"));
        interactions.bookmark(author, post.publicId());

        interactions.cancelBookmark(author, post.publicId());

        assertThat(feedOne(post.publicId()).bookmarkCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("非收藏人取消收藏：403 POST_FORBIDDEN，计数不变")
    void nonBookmarkerCannotCancelBookmark() {
        VerifiedToken author = newToken("作者甲");
        VerifiedToken other = newToken("路人乙");
        PostView post = posts.publish(author, published("成都三日"));
        interactions.bookmark(author, post.publicId());

        BizException error = rejectionOf(() -> interactions.cancelBookmark(other, post.publicId()));

        assertThat(error.getErrorCode()).isEqualTo(PostErrorEnum.POST_FORBIDDEN.getErrorCode());
        assertThat(feedOne(post.publicId()).bookmarkCount()).isEqualTo(1L);
    }

    // ---------- 互动与帖子存在的判定次序 ----------

    @Test
    @DisplayName("对不存在的帖子互动：404 POST_NOT_FOUND")
    void interactionOnMissingPostReturnsNotFound() {
        VerifiedToken author = newToken("作者甲");
        String missing = Ulid.generate();

        assertThat(rejectionOf(() -> interactions.createComment(author, missing,
                new CommentCreateCommand("x", null))).getErrorCode())
                .isEqualTo(PostErrorEnum.POST_NOT_FOUND.getErrorCode());
        assertThat(rejectionOf(() -> interactions.vote(author, missing,
                new VoteCreateCommand(VoteDirection.UP))).getErrorCode())
                .isEqualTo(PostErrorEnum.POST_NOT_FOUND.getErrorCode());
        assertThat(rejectionOf(() -> interactions.bookmark(author, missing)).getErrorCode())
                .isEqualTo(PostErrorEnum.POST_NOT_FOUND.getErrorCode());
    }

    @Test
    @DisplayName("本人状态为 null/false 或有效 up/down；账户隔离，取消后不复活旧记录")
    void ownStateIsEffectiveAndAccountIsolated() {
        VerifiedToken a = newToken("账户甲");
        VerifiedToken b = newToken("账户乙");
        String publicId = posts.publish(a, published("本人状态")).publicId();
        assertState(a, publicId, null, false);
        assertState(b, publicId, null, false);
        interactions.vote(a, publicId, new VoteCreateCommand(VoteDirection.DOWN));
        interactions.bookmark(a, publicId);
        assertState(a, publicId, VoteDirection.DOWN, true);
        assertState(b, publicId, null, false);
        interactions.vote(b, publicId, new VoteCreateCommand(VoteDirection.UP));
        assertState(b, publicId, VoteDirection.UP, false);
        interactions.cancelVote(a, publicId);
        interactions.cancelBookmark(a, publicId);
        assertState(a, publicId, null, false);
        assertState(b, publicId, VoteDirection.UP, false);
        assertThat(posts.read(publicId, a).upVoteCount()).isEqualTo(1L);
        interactions.cancelVote(b, publicId);
        assertState(b, publicId, null, false);
        assertThat(posts.read(publicId, a).upVoteCount()).isZero();
        interactions.vote(a, publicId, new VoteCreateCommand(VoteDirection.UP));
        interactions.bookmark(a, publicId);
        assertState(a, publicId, VoteDirection.UP, true);
    }

    @Test
    @DisplayName("个人状态共享草稿、已删除与不存在的读取边界")
    void ownStateUsesPostReadVisibility() {
        VerifiedToken author = newToken("草稿作者");
        VerifiedToken other = newToken("其它账户");
        String publicId = posts.publish(author,
                new PostCommand("草稿", "内容", null, List.of(), PostStatus.DRAFT)).publicId();
        assertState(author, publicId, null, false);
        assertNotFound(() -> interactions.readMyInteractions(other, publicId));
        posts.delete(author, publicId);
        assertNotFound(() -> interactions.readMyInteractions(author, publicId));
        assertNotFound(() -> interactions.readMyInteractions(other, Ulid.generate()));
    }

    @Test
    @DisplayName("列表返回真实父标识：同页、跨页、父软删除与历史深层回复均不丢失关系")
    void commentListResolvesRealParentsAcrossPagesAndDeletion() {
        VerifiedToken author = newToken("评论作者");
        String publicId = posts.publish(author, published("父关系")).publicId();
        CommentView parent = interactions.createComment(author, publicId, new CommentCreateCommand("父正文", null));
        CommentView reply = interactions.createComment(author, publicId, new CommentCreateCommand("回复正文", parent.publicId()));
        CommentPage samePage = interactions.listComments(publicId, null, null, 20);
        assertThat(samePage.items()).extracting(CommentView::publicId).containsExactly(reply.publicId(), parent.publicId());
        assertThat(samePage.items().get(0).parentPublicId()).isEqualTo(parent.publicId());
        CommentPage firstPage = interactions.listComments(publicId, null, null, 1);
        assertThat(firstPage.items().get(0).parentPublicId()).isEqualTo(parent.publicId());
        assertThat(interactions.listComments(publicId, null, firstPage.nextCursor(), 1).items().get(0).parentPublicId()).isNull();
        CommentView deeper = interactions.createComment(author, publicId, new CommentCreateCommand("历史深层", reply.publicId()));
        interactions.deleteComment(author, publicId, parent.publicId());
        CommentPage remaining = interactions.listComments(publicId, null, null, 20);
        assertThat(remaining.items()).extracting(CommentView::publicId).containsExactly(deeper.publicId(), reply.publicId());
        assertThat(remaining.items()).extracting(CommentView::content).doesNotContain("父正文");
        assertThat(remaining.items().get(0).parentPublicId()).isEqualTo(reply.publicId());
        assertThat(remaining.items().get(1).parentPublicId()).isEqualTo(parent.publicId());
        assertThat(posts.read(publicId, null).commentCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("不存在、跨帖和已删除父评论目标均 404，不建立回复")
    void invalidReplyTargetsDoNotCreateComments() {
        VerifiedToken author = newToken("评论作者");
        String publicId = posts.publish(author, published("当前帖子")).publicId();
        String otherPost = posts.publish(author, published("其它帖子")).publicId();
        CommentView crossPost = interactions.createComment(author, otherPost, new CommentCreateCommand("其它正文", null));
        CommentView deleted = interactions.createComment(author, publicId, new CommentCreateCommand("已删父", null));
        interactions.deleteComment(author, publicId, deleted.publicId());
        for (String parent : List.of(Ulid.generate(), crossPost.publicId(), deleted.publicId())) {
            assertNotFound(() -> interactions.createComment(author, publicId, new CommentCreateCommand("不应落库", parent)));
        }
        assertThat(interactions.listComments(publicId, null, null, 20).items()).isEmpty();
        assertThat(posts.read(publicId, null).commentCount()).isZero();
    }

    @Test
    @DisplayName("评论默认 20、上限 50、非法游标 400；相同创建时间按 id 稳定续页到底")
    void commentPagingLimitsAndStableContinuation() {
        VerifiedToken author = newToken("分页作者");
        String publicId = posts.publish(author, published("评论分页")).publicId();
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 55; index++) {
            ids.add(interactions.createComment(author, publicId, new CommentCreateCommand("评论" + index, null)).publicId());
        }
        entityManager.flush();
        entityManager.createNativeQuery("update post_comments set created_at = '2026-01-01 00:00:00.000' "
                        + "where post_id = (select id from posts where public_id = :post)")
                .setParameter("post", publicId).executeUpdate();
        entityManager.clear();
        Collections.reverse(ids);
        CommentPage first = interactions.listComments(publicId, null, null, null);
        assertThat(first.items()).extracting(CommentView::publicId).containsExactlyElementsOf(ids.subList(0, 20));
        CommentPage second = interactions.listComments(publicId, null, first.nextCursor(), null);
        assertThat(second.items()).extracting(CommentView::publicId).containsExactlyElementsOf(ids.subList(20, 40));
        CommentPage last = interactions.listComments(publicId, null, second.nextCursor(), null);
        assertThat(last.items()).extracting(CommentView::publicId).containsExactlyElementsOf(ids.subList(40, 55));
        assertThat(last.nextCursor()).isNull();
        assertThat(interactions.listComments(publicId, null, null, 500).items()).hasSize(50);
        assertThat(rejectionOf(() -> interactions.listComments(publicId, null, "broken-cursor", 20)).getErrorCode())
                .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
    }

    @Test
    @DisplayName("Java trim 与 UTF-16 边界：2000 代码单元可写，2001、null 和控制空白被拒")
    void commentContentUsesJavaTrimAndUtf16Length() {
        VerifiedToken author = newToken("边界作者");
        String publicId = posts.publish(author, published("内容边界")).publicId();
        String emoji = "😀".repeat(1000);
        assertThat(interactions.createComment(author, publicId, new CommentCreateCommand("\u0000\u001f " + emoji + " \u0001", null)).content())
                .isEqualTo(emoji).hasSize(2000);
        assertThat(interactions.createComment(author, publicId, new CommentCreateCommand("\u00a0", null)).content()).isEqualTo("\u00a0");
        for (String invalid : new String[]{null, "\u0000\u001f ", emoji + "x", "x".repeat(2001)}) {
            assertThat(rejectionOf(() -> interactions.createComment(author, publicId, new CommentCreateCommand(invalid, null))).getErrorCode())
                    .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
        }
        assertThat(posts.read(publicId, author).commentCount()).isEqualTo(2L);
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

    private static PostCommand published(String title) {
        return new PostCommand(title, "正文内容", "https://cdn.example.com/cover.png",
                List.of("攻略", "成都"), PostStatus.PUBLISHED);
    }

    private PostSummary feedOne(String publicId) {
        PostPage page = posts.feed(null, null, 10);
        return page.items().stream()
                .filter(item -> item.publicId().equals(publicId))
                .findFirst()
                .orElseThrow();
    }

    private void assertState(VerifiedToken viewer, String publicId, VoteDirection direction, boolean bookmarked) {
        var state = interactions.readMyInteractions(viewer, publicId);
        assertThat(state.voteDirection()).isEqualTo(direction);
        assertThat(state.bookmarked()).isEqualTo(bookmarked);
    }

    private static void assertNotFound(ThrowingCallable call) {
        BizException error = rejectionOf(call);
        assertThat(error.getErrorCode()).isEqualTo(PostErrorEnum.POST_NOT_FOUND.getErrorCode());
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static BizException rejectionOf(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BizException.class);
        return (BizException) thrown;
    }
}
