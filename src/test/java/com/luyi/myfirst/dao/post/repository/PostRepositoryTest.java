package com.luyi.myfirst.dao.post.repository;

import com.luyi.myfirst.common.enums.PostStatus;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.post.entity.Post;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 帖子持久化的两条底线：对外标识能查到、软删除后查不到；
 * 以及游标分页在「同一毫秒发布」这种边界上不重不漏。
 *
 * 连真实 MySQL（与 02-test 的凭据注入同一约定）：生成列、CHECK、JSON 列这些语义
 * 用内存替身验不出来，而它们是这张表最容易写错的部分。
 *
 * {@code @Transactional} 让每个用例回滚，种下的账户与帖子不会留在库里。
 */
@SpringBootTest
@Transactional
class PostRepositoryTest {

    @Autowired
    private PostRepository posts;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("保存后可按对外标识查到，标签与状态往返无损")
    void savedPostIsFindableByPublicId() {
        Account author = seedAccount();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Post post = Post.create(Ulid.generate(), author.id(), "A walk at dusk", "Body", "https://cdn.example.com/a.jpg",
                List.of("walk", "dusk"), PostStatus.PUBLISHED, now);

        posts.saveAndFlush(post);

        Post found = posts.findByPublicIdAndDeletedAtIsNull(post.publicId()).orElseThrow();
        assertThat(found.id()).isEqualTo(post.id());
        assertThat(found.authorId()).isEqualTo(author.id());
        assertThat(found.status()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(found.publishedAt()).isEqualTo(now);
        assertThat(found.tags()).containsExactly("walk", "dusk");
        assertThat(found.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("软删除后按对外标识查不到，但行仍在且留下删除时间")
    void softDeletedPostIsInvisibleButRowSurvives() {
        Account author = seedAccount();
        Post post = Post.create(Ulid.generate(), author.id(), "Temporary", "Body", null,
                List.of(), PostStatus.PUBLISHED, Instant.now());
        posts.saveAndFlush(post);

        post.delete(Instant.now());
        posts.saveAndFlush(post);

        assertThat(posts.findByPublicIdAndDeletedAtIsNull(post.publicId())).isEmpty();
        Post row = posts.findById(post.id()).orElseThrow();
        assertThat(row.deletedAt()).isNotNull();
    }

    @Test
    @DisplayName("同一毫秒发布的 5 条帖子：两页游标翻页不重不漏")
    void keysetPagingIsStableAtSameMillisecond() {
        Account author = seedAccount();
        Instant sameInstant = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<Post> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Post post = Post.create(Ulid.generate(), author.id(), "Post " + i, "Body", null,
                    List.of(), PostStatus.PUBLISHED, sameInstant);
            created.add(posts.saveAndFlush(post));
        }

        List<Post> firstPage = posts.findFeed(PostStatus.PUBLISHED, PageRequest.ofSize(2));
        assertThat(firstPage).hasSize(2);
        Post lastOfFirst = firstPage.get(firstPage.size() - 1);

        List<Post> secondPage = posts.findFeedAfter(PostStatus.PUBLISHED, lastOfFirst.publishedAt(),
                lastOfFirst.id(), PageRequest.ofSize(2));
        assertThat(secondPage).hasSize(2);

        List<Long> seen = new ArrayList<>();
        firstPage.forEach(p -> seen.add(p.id()));
        secondPage.forEach(p -> seen.add(p.id()));
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).allMatch(id -> created.stream().anyMatch(p -> p.id().equals(id)));
        // id 倒序兜底：同一毫秒里靠 id 决定先后，否则同秒的第 3 条会既不在第一页也不在第二页。
        assertThat(seen).isSortedAccordingTo((left, right) -> Long.compare(right, left));
    }

    @Test
    @DisplayName("计数增量：三列各自 +1/-1，updatedAt 不变，已软删除的帖子更新 0 行")
    void counterIncrementsAreAtomicAndSkipSoftDeleted() {
        Account author = seedAccount();
        Post post = Post.create(Ulid.generate(), author.id(), "Counter", "Body", null,
                List.of(), PostStatus.PUBLISHED, Instant.now());
        Post saved = posts.saveAndFlush(post);
        Long id = saved.id();
        Instant updatedBefore = saved.updatedAt();

        assertThat(posts.incrementUpVoteCount(id, 1)).isEqualTo(1);
        assertThat(posts.incrementCommentCount(id, 1)).isEqualTo(1);
        assertThat(posts.incrementBookmarkCount(id, 1)).isEqualTo(1);

        // 增量是原生 update：clearAutomatically 已清一级缓存，重查读到的就是库里的列值。
        Post afterIncrement = posts.findById(id).orElseThrow();
        assertThat(afterIncrement.upVoteCount()).isEqualTo(1);
        assertThat(afterIncrement.commentCount()).isEqualTo(1);
        assertThat(afterIncrement.bookmarkCount()).isEqualTo(1);
        // 计数变化不能刷新 updated_at：一次点赞把帖子顶到 feed 顶部是另一种 bug。
        assertThat(afterIncrement.updatedAt()).isEqualTo(updatedBefore);

        assertThat(posts.incrementUpVoteCount(id, -1)).isEqualTo(1);
        assertThat(posts.findById(id).orElseThrow().upVoteCount()).isZero();

        // 软删除之后增量必须落空：where deleted_at is null 把行挡在外面，
        // 否则计数会在不可见的行上继续漂移。
        afterIncrement.delete(Instant.now());
        posts.saveAndFlush(afterIncrement);
        assertThat(posts.incrementUpVoteCount(id, 1)).isZero();
        assertThat(posts.findById(id).orElseThrow().upVoteCount()).isZero();
    }

    @Test
    @DisplayName("按计数排序：upVoteCount / commentCount 降序，计数游标续页不跳过不重复")
    void countOrderedFeedsRespectCursor() {
        Account author = seedAccount();
        Post low = posts.saveAndFlush(Post.create(Ulid.generate(), author.id(), "Low", "Body", null,
                List.of(), PostStatus.PUBLISHED, Instant.now()));
        Post mid = posts.saveAndFlush(Post.create(Ulid.generate(), author.id(), "Mid", "Body", null,
                List.of(), PostStatus.PUBLISHED, Instant.now()));
        Post high = posts.saveAndFlush(Post.create(Ulid.generate(), author.id(), "High", "Body", null,
                List.of(), PostStatus.PUBLISHED, Instant.now()));
        posts.incrementUpVoteCount(low.id(), 1);
        posts.incrementUpVoteCount(mid.id(), 2);
        posts.incrementUpVoteCount(high.id(), 3);
        posts.incrementCommentCount(mid.id(), 5);

        assertThat(posts.findFeedByUpVoteCount(PostStatus.PUBLISHED, PageRequest.ofSize(10)))
                .extracting(Post::id)
                .containsSubsequence(high.id(), mid.id(), low.id());
        assertThat(posts.findFeedByCommentCount(PostStatus.PUBLISHED, PageRequest.ofSize(10)))
                .extracting(Post::id)
                .startsWith(mid.id());

        // 计数游标：首页末条是 3 票的 high，续页必须从 2 票开始。
        // 若游标条件只比较 id（旧实现的残留写法），mid 会被整段跳过。
        List<Post> firstPage = posts.findFeedByUpVoteCount(PostStatus.PUBLISHED, PageRequest.ofSize(1));
        assertThat(firstPage).hasSize(1);
        Post cursor = firstPage.get(0);
        assertThat(cursor.id()).isEqualTo(high.id());
        assertThat(posts.findFeedByUpVoteCountAfter(PostStatus.PUBLISHED, cursor.upVoteCount(),
                cursor.publishedAt(), cursor.id(), PageRequest.ofSize(1)))
                .extracting(Post::id)
                .contains(mid.id())
                .doesNotContain(high.id());
    }

    @Test
    @DisplayName("计数列不可为负：直接写入负值被 CHECK 约束拒绝")
    void negativeCounterIsRejectedByCheckConstraint() {
        Account author = seedAccount();
        Post saved = posts.saveAndFlush(Post.create(Ulid.generate(), author.id(), "Negative", "Body", null,
                List.of(), PostStatus.PUBLISHED, Instant.now()));

        assertThatThrownBy(() -> jdbc.update("update posts set up_vote_count = -1 where id = ?", saved.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_posts_up_vote_count");
    }

    @Test
    @DisplayName("用户页列表：只返回该作者的帖子，他人帖子不出现")
    void authorFeedFiltersByAuthor() {
        Account author = seedAccount();
        Account other = seedAccount();
        Post mine = posts.saveAndFlush(Post.create(Ulid.generate(), author.id(), "Mine", "Body", null,
                List.of(), PostStatus.PUBLISHED, Instant.now()));
        posts.saveAndFlush(Post.create(Ulid.generate(), other.id(), "Theirs", "Body", null,
                List.of(), PostStatus.PUBLISHED, Instant.now()));

        assertThat(posts.findPublishedByAuthor(author.id(), PostStatus.PUBLISHED, PageRequest.ofSize(10)))
                .extracting(Post::publicId)
                .containsExactly(mine.publicId());
    }

    @Test
    @DisplayName("用户页列表：作者本人的草稿也不出现")
    void authorFeedSkipsDrafts() {
        Account author = seedAccount();
        posts.saveAndFlush(Post.create(Ulid.generate(), author.id(), "Draft", "Body", null,
                List.of(), PostStatus.DRAFT, Instant.now()));

        assertThat(posts.findPublishedByAuthor(author.id(), PostStatus.PUBLISHED, PageRequest.ofSize(10)))
                .isEmpty();
    }

    @Test
    @DisplayName("用户页列表：已软删除的帖子不出现")
    void authorFeedSkipsSoftDeleted() {
        Account author = seedAccount();
        Post post = posts.saveAndFlush(Post.create(Ulid.generate(), author.id(), "Gone", "Body", null,
                List.of(), PostStatus.PUBLISHED, Instant.now()));
        post.delete(Instant.now());
        posts.saveAndFlush(post);

        assertThat(posts.findPublishedByAuthor(author.id(), PostStatus.PUBLISHED, PageRequest.ofSize(10)))
                .isEmpty();
    }

    @Test
    @DisplayName("用户页列表：同一毫秒两条按 id 倒序，游标之后不重复不遗漏")
    void authorFeedCursorIsStableAtSameMillisecond() {
        Account author = seedAccount();
        Instant sameInstant = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Post first = posts.saveAndFlush(Post.create(Ulid.generate(), author.id(), "First", "Body", null,
                List.of(), PostStatus.PUBLISHED, sameInstant));
        Post second = posts.saveAndFlush(Post.create(Ulid.generate(), author.id(), "Second", "Body", null,
                List.of(), PostStatus.PUBLISHED, sameInstant));

        List<Post> page = posts.findPublishedByAuthor(author.id(), PostStatus.PUBLISHED, PageRequest.ofSize(10));
        assertThat(page).extracting(Post::id).containsExactly(second.id(), first.id());

        Post cursor = page.get(0);
        assertThat(posts.findPublishedByAuthorAfter(author.id(), PostStatus.PUBLISHED,
                cursor.publishedAt(), cursor.id(), PageRequest.ofSize(10)))
                .extracting(Post::id)
                .containsExactly(first.id());
    }

    private Account seedAccount() {
        String publicId = Ulid.generate();
        Account account = Account.createActive(publicId, publicId.toLowerCase() + "@example.com",
                "hash-not-used", "Tester");
        return accounts.saveAndFlush(account);
    }
}
