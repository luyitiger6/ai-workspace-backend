package com.luyi.myfirst.service.post;

import com.luyi.myfirst.common.enums.PostStatus;
import com.luyi.myfirst.common.enums.VoteDirection;
import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.exception.PostErrorEnum;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.post.entity.Post;
import com.luyi.myfirst.dao.post.repository.PostRepository;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.post.interaction.PostInteractionService;
import com.luyi.myfirst.service.post.interaction.model.CommentCreateCommand;
import com.luyi.myfirst.service.post.interaction.model.VoteCreateCommand;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 服务层的读写契约（spec R2/R3/R4/R5/R9 中可由服务层表达的部分）。
 *
 * 401 与「跨站先于认证」这两类判定不在这里：它们发生在拦截器与 MockMvc 之间，
 * 服务层拿到的永远是已认证身份。对应 Scenario 在 web 层的用例里覆盖。
 */
@SpringBootTest
@Transactional
class PostServiceTest {

    @Autowired
    private PostService service;

    @Autowired
    private PostRepository posts;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PostInteractionService interactions;

    // ---------- R2 发布需登录且作者取自令牌 ----------

    @Test
    @DisplayName("登录后发帖：返回详情，作者是令牌所代表的账户")
    void publishBindsAuthorToToken() {
        VerifiedToken author = newToken("作者甲");

        PostView created = service.publish(author, published("成都三日"));

        assertThat(created.publicId()).hasSize(26);
        assertThat(created.authorPublicId()).isEqualTo(author.account().publicId());
        assertThat(created.status()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(created.tags()).containsExactly("攻略", "成都");
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("请求体不得指定作者：换一张令牌发布，落库作者随之改变")
    void authorComesFromTokenNotPayload() {
        VerifiedToken first = newToken("作者甲");
        VerifiedToken second = newToken("作者乙");

        PostView byFirst = service.publish(first, published("甲的文章"));
        PostView bySecond = service.publish(second, published("乙的文章"));

        assertThat(service.read(byFirst.publicId(), null).authorPublicId())
                .isEqualTo(first.account().publicId());
        assertThat(service.read(bySecond.publicId(), null).authorPublicId())
                .isEqualTo(second.account().publicId());
    }

    // ---------- R3 编辑与删除仅限作者本人 ----------

    @Test
    @DisplayName("作者编辑成功：内容更新，更新时间不早于创建时间")
    void authorEditsOwnPost() {
        VerifiedToken author = newToken("作者甲");
        PostView created = service.publish(author, draft("草稿标题"));

        PostView edited = service.edit(author, created.publicId(),
                new PostCommand("  改过的标题  ", "改过的正文", "https://cdn.example.com/b.png",
                        List.of("新标签"), PostStatus.PUBLISHED));

        assertThat(edited.title()).isEqualTo("改过的标题");
        assertThat(edited.content()).isEqualTo("改过的正文");
        assertThat(edited.coverImage()).isEqualTo("https://cdn.example.com/b.png");
        assertThat(edited.status()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(edited.updatedAt()).isAfterOrEqualTo(created.createdAt());
    }

    @Test
    @DisplayName("非作者编辑被拒：403 POST_FORBIDDEN，内容保持原样")
    void nonAuthorCannotEdit() {
        VerifiedToken author = newToken("作者甲");
        VerifiedToken other = newToken("路人乙");
        PostView created = service.publish(author, published("原样内容"));

        BizException error = rejectionOf(() -> service.edit(other, created.publicId(), published("被篡改")));

        assertThat(error.getErrorCode()).isEqualTo(PostErrorEnum.POST_FORBIDDEN.getErrorCode());
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(service.read(created.publicId(), null).title()).isEqualTo("原样内容");
    }

    @Test
    @DisplayName("非作者删除被拒：403 POST_FORBIDDEN，帖子仍可读")
    void nonAuthorCannotDelete() {
        VerifiedToken author = newToken("作者甲");
        VerifiedToken other = newToken("路人乙");
        PostView created = service.publish(author, published("留着"));

        BizException error = rejectionOf(() -> service.delete(other, created.publicId()));

        assertThat(error.getErrorCode()).isEqualTo(PostErrorEnum.POST_FORBIDDEN.getErrorCode());
        assertThat(service.read(created.publicId(), null).title()).isEqualTo("留着");
    }

    @Test
    @DisplayName("判定次序：已登录账户操作不存在的标识得到 404，而不是 403")
    void missingPostIsNotFoundNotForbidden() {
        VerifiedToken someone = newToken("路人乙");

        BizException onEdit = rejectionOf(() -> service.edit(someone, Ulid.generate(), published("x")));
        BizException onDelete = rejectionOf(() -> service.delete(someone, Ulid.generate()));

        assertThat(onEdit.getErrorCode()).isEqualTo(PostErrorEnum.POST_NOT_FOUND.getErrorCode());
        assertThat(onEdit.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(onDelete.getErrorCode()).isEqualTo(PostErrorEnum.POST_NOT_FOUND.getErrorCode());
    }

    @Test
    @DisplayName("作者删除是软删除：读不到，但数据行仍在且留有删除时间")
    void authorDeleteIsSoft() {
        VerifiedToken author = newToken("作者甲");
        PostView created = service.publish(author, published("将被删除"));
        Long rowId = posts.findByPublicIdAndDeletedAtIsNull(created.publicId()).orElseThrow().id();

        service.delete(author, created.publicId());

        BizException gone = rejectionOf(() -> service.read(created.publicId(), author));
        assertThat(gone.getErrorCode()).isEqualTo(PostErrorEnum.POST_NOT_FOUND.getErrorCode());

        // 软删除的取证必须绕开「按未删除条件查」：那条查询正是被验证为查不到的对象。
        Post row = posts.findById(rowId).orElseThrow();
        assertThat(row.isDeleted()).isTrue();
        assertThat(row.deletedAt()).isNotNull();
    }

    // ---------- R4 列表公开且只含已发布 ----------

    @Test
    @DisplayName("游客可读列表：不需要令牌")
    void guestReadsFeed() {
        VerifiedToken author = newToken("作者甲");
        PostView created = service.publish(author, published("公开可见"));

        PostPage page = service.feed(null, null, null);

        assertThat(page.items()).extracting(PostSummary::publicId).contains(created.publicId());
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("草稿不出现在列表")
    void draftsNeverReachFeed() {
        VerifiedToken author = newToken("作者甲");
        PostView draft = service.publish(author, draft("还没写完"));

        PostPage page = service.feed(null, null, 50);

        assertThat(page.items()).extracting(PostSummary::publicId).doesNotContain(draft.publicId());
    }

    @Test
    @DisplayName("游标翻页：不重复、不遗漏，翻页期间新增帖子也不打乱前一页")
    void keysetPagingDoesNotRepeatOrSkip() {
        VerifiedToken author = newToken("作者甲");
        List<String> publishedIds = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            publishedIds.add(service.publish(author, published("第 " + index + " 篇")).publicId());
        }

        PostPage first = service.feed(null, null, 2);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();

        // 翻页途中新增一条：keyset 只认 (published_at, id)，新帖排在前面，不会挤进第二页。
        service.publish(author, published("插队的新帖"));

        PostPage second = service.feed(null, first.nextCursor(), 2);
        List<String> firstIds = first.items().stream().map(PostSummary::publicId).toList();
        List<String> secondIds = second.items().stream().map(PostSummary::publicId).toList();

        assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);
        assertThat(second.nextCursor()).isNull();
        assertThat(firstIds.size() + secondIds.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("非法游标：400 VALIDATION_FAILED")
    void brokenCursorIsRejected() {
        BizException error = rejectionOf(() -> service.feed(null, "not-a-cursor", 20));

        assertThat(error.getErrorCode()).isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("limit 超过上限：最多返回 50 条")
    void limitIsCappedAtFifty() {
        VerifiedToken author = newToken("作者甲");
        for (int index = 0; index < 55; index++) {
            service.publish(author, published("批量 " + index));
        }

        PostPage page = service.feed(null, null, 500);

        assertThat(page.items()).hasSize(50);
        assertThat(page.nextCursor()).isNotNull();
    }

    // ---------- R4 补充：排序切换、游标不混用、列表项含统计 ----------

    @Test
    @DisplayName("列表项默认携带三个互动统计字段，无互动时为 0")
    void feedSummaryCarriesInteractionCounts() {
        VerifiedToken author = newToken("作者甲");
        PostView post = service.publish(author, published("统计测试"));

        PostSummary summary = service.feed(null, null, 10).items().stream()
                .filter(item -> item.publicId().equals(post.publicId()))
                .findFirst()
                .orElseThrow();

        assertThat(summary.commentCount()).isEqualTo(0L);
        assertThat(summary.upVoteCount()).isEqualTo(0L);
        assertThat(summary.bookmarkCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("按点赞数排序：被点赞帖子排在前面")
    void feedByUpVoteCount() {
        VerifiedToken author = newToken("作者甲");
        PostView first = service.publish(author, published("先发布"));
        PostView second = service.publish(author, published("后发布"));
        interactions.vote(author, first.publicId(), new VoteCreateCommand(VoteDirection.UP));

        PostPage page = service.feed("upVoteCount", null, 10);

        assertThat(page.items()).extracting(PostSummary::publicId).startsWith(first.publicId());
        assertThat(page.items().get(0).upVoteCount()).isEqualTo(1L);
        assertThat(page.items().stream()
                .filter(item -> item.publicId().equals(second.publicId()))
                .findFirst()
                .orElseThrow()
                .upVoteCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("按评论数排序：被评论帖子排在前面")
    void feedByCommentCount() {
        VerifiedToken author = newToken("作者甲");
        PostView first = service.publish(author, published("有评论"));
        PostView second = service.publish(author, published("无评论"));
        interactions.createComment(author, first.publicId(), new CommentCreateCommand("一条评论", null));

        PostPage page = service.feed("commentCount", null, 10);

        assertThat(page.items()).extracting(PostSummary::publicId).startsWith(first.publicId());
        assertThat(page.items().get(0).commentCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("不同排序的游标不混用：publishedAt 游标塞进 upVoteCount 请求报 400")
    void cursorSortMismatchIsRejected() {
        VerifiedToken author = newToken("作者甲");
        service.publish(author, published("第一页"));
        service.publish(author, published("第二页"));

        PostPage first = service.feed(null, null, 1);
        assertThat(first.nextCursor()).isNotNull();

        BizException error = rejectionOf(() -> service.feed("upVoteCount", first.nextCursor(), 10));
        assertThat(error.getErrorCode()).isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("非法 sortBy：400 VALIDATION_FAILED")
    void invalidSortByIsRejected() {
        BizException error = rejectionOf(() -> service.feed("hot", null, 10));
        assertThat(error.getErrorCode()).isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------- R5 详情公开且草稿对他人不可见 ----------

    @Test
    @DisplayName("游客读已发布详情")
    void guestReadsPublishedDetail() {
        VerifiedToken author = newToken("作者甲");
        PostView created = service.publish(author, published("公开详情"));

        PostView read = service.read(created.publicId(), null);

        assertThat(read.publicId()).isEqualTo(created.publicId());
        assertThat(read.content()).isEqualTo(created.content());
    }

    @Test
    @DisplayName("游客读草稿：404 而不是 403")
    void guestReadingDraftGetsNotFound() {
        VerifiedToken author = newToken("作者甲");
        PostView created = service.publish(author, draft("私有草稿"));

        BizException error = rejectionOf(() -> service.read(created.publicId(), null));

        assertThat(error.getErrorCode()).isEqualTo(PostErrorEnum.POST_NOT_FOUND.getErrorCode());
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("作者读自己的草稿")
    void authorReadsOwnDraft() {
        VerifiedToken author = newToken("作者甲");
        PostView created = service.publish(author, draft("私有草稿"));

        assertThat(service.read(created.publicId(), author).title()).isEqualTo("私有草稿");
    }

    @Test
    @DisplayName("已删除的帖子对所有人 404，包括原作者")
    void deletedPostIsUnreadableForEveryone() {
        VerifiedToken author = newToken("作者甲");
        PostView created = service.publish(author, published("删除测试"));
        service.delete(author, created.publicId());

        assertThat(rejectionOf(() -> service.read(created.publicId(), author)).getErrorCode())
                .isEqualTo(PostErrorEnum.POST_NOT_FOUND.getErrorCode());
        assertThat(rejectionOf(() -> service.read(created.publicId(), null)).getErrorCode())
                .isEqualTo(PostErrorEnum.POST_NOT_FOUND.getErrorCode());
    }

    // ---------- R9 入参校验 ----------

    @Test
    @DisplayName("空标题被拒：只含空白也算空，且不写库")
    void blankTitleIsRejected() {
        VerifiedToken author = newToken("作者甲");
        long before = posts.count();

        BizException error = rejectionOf(() -> service.publish(author, command("   ", "正文", null, List.of(), PostStatus.DRAFT)));

        assertThat(error.getErrorCode()).isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
        assertThat(posts.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("标题边界：120 字符通过、121 字符被拒")
    void titleLengthBoundary() {
        VerifiedToken author = newToken("作者甲");

        PostView ok = service.publish(author, command("标".repeat(120), "正文", null, List.of(), PostStatus.DRAFT));
        assertThat(ok.title()).hasSize(120);

        assertThat(rejectionOf(() -> service.publish(author,
                command("标".repeat(121), "正文", null, List.of(), PostStatus.DRAFT))).getErrorCode())
                .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
    }

    @Test
    @DisplayName("正文边界：65535 字节通过、65536 字节被拒且不写库")
    void contentByteBoundary() {
        VerifiedToken author = newToken("作者甲");
        String justFits = "a".repeat(65535);
        long before = posts.count();

        assertThat(service.publish(author, command("够长", justFits, null, List.of(), PostStatus.DRAFT)).content())
                .hasSize(65535);

        BizException error = rejectionOf(() -> service.publish(author,
                command("太长", "a".repeat(65536), null, List.of(), PostStatus.DRAFT)));
        assertThat(error.getErrorCode()).isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
        assertThat(posts.count()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("标签：最多 10 个，11 个被拒")
    void tagCountBoundary() {
        VerifiedToken author = newToken("作者甲");
        List<String> ten = new ArrayList<>();
        List<String> eleven = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            eleven.add("tag" + index);
            if (index < 10) {
                ten.add("tag" + index);
            }
        }

        assertThat(service.publish(author, command("十个标签", "正文", null, ten, PostStatus.DRAFT)).tags())
                .hasSize(10);

        assertThat(rejectionOf(() -> service.publish(author,
                command("十一个标签", "正文", null, eleven, PostStatus.DRAFT))).getErrorCode())
                .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
    }

    @Test
    @DisplayName("标签去重后落库：读回来每个标签只出现一次")
    void duplicateTagsAreDeduplicatedBeforePersist() {
        VerifiedToken author = newToken("作者甲");

        PostView created = service.publish(author,
                command("重复标签", "正文", null, List.of("成都", "成都", "攻略"), PostStatus.DRAFT));

        // 清一级缓存，确保读到的是 JSON 列的真实往返结果，而不是内存里那个列表。
        entityManager.flush();
        entityManager.clear();

        PostView reloaded = service.read(created.publicId(), author);
        assertThat(reloaded.tags()).containsExactly("成都", "攻略");
    }

    @Test
    @DisplayName("标签长度边界：32 字符通过、33 字符与空标签被拒")
    void tagLengthBoundary() {
        VerifiedToken author = newToken("作者甲");

        assertThat(service.publish(author, command("长标签", "正文", null, List.of("标".repeat(32)), PostStatus.DRAFT))
                .tags()).hasSize(1);

        assertThat(rejectionOf(() -> service.publish(author,
                command("超长标签", "正文", null, List.of("标".repeat(33)), PostStatus.DRAFT))).getErrorCode())
                .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());

        assertThat(rejectionOf(() -> service.publish(author,
                command("空标签", "正文", null, List.of("  "), PostStatus.DRAFT))).getErrorCode())
                .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
    }

    @Test
    @DisplayName("封面：空串归一为 null，非 http(s) 绝对地址被拒")
    void coverImageRules() {
        VerifiedToken author = newToken("作者甲");

        assertThat(service.publish(author, command("无封面", "正文", "  ", List.of(), PostStatus.DRAFT))
                .coverImage()).isNull();
        assertThat(service.publish(author, command("有封面", "正文", "https://cdn.example.com/a.png",
                List.of(), PostStatus.DRAFT)).coverImage()).isEqualTo("https://cdn.example.com/a.png");

        for (String broken : List.of("not-a-url", "ftp://cdn.example.com/a.png", "javascript:alert(1)", "/relative.png")) {
            assertThat(rejectionOf(() -> service.publish(author,
                    command("坏封面", "正文", broken, List.of(), PostStatus.DRAFT))).getErrorCode())
                    .as("封面 %s 应被拒", broken)
                    .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode());
        }
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
        return command(title, "正文内容", "https://cdn.example.com/cover.png", List.of("攻略", "成都"), PostStatus.PUBLISHED);
    }

    private static PostCommand draft(String title) {
        return command(title, "草稿正文", null, List.of(), PostStatus.DRAFT);
    }

    private static PostCommand command(String title, String content, String coverImage,
                                       List<String> tags, PostStatus status) {
        return new PostCommand(title, content, coverImage, tags, status);
    }

    private static BizException rejectionOf(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BizException.class);
        return (BizException) thrown;
    }
}