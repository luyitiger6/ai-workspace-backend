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
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

    private Account seedAccount() {
        String publicId = Ulid.generate();
        Account account = Account.createActive(publicId, publicId.toLowerCase() + "@example.com",
                "hash-not-used", "Tester");
        return accounts.saveAndFlush(account);
    }
}
