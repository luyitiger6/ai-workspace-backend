package com.luyi.myfirst.service.post.impl;

import com.luyi.myfirst.common.enums.PostSortBy;
import com.luyi.myfirst.common.enums.PostStatus;
import com.luyi.myfirst.common.enums.VoteDirection;
import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.exception.PostErrorEnum;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.post.entity.Post;
import com.luyi.myfirst.dao.post.repository.BookmarkRepository;
import com.luyi.myfirst.dao.post.repository.CommentRepository;
import com.luyi.myfirst.dao.post.repository.PostRepository;
import com.luyi.myfirst.dao.post.repository.VoteRepository;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.post.PostService;
import com.luyi.myfirst.service.post.model.PostCommand;
import com.luyi.myfirst.service.post.model.PostPage;
import com.luyi.myfirst.service.post.model.PostSummary;
import com.luyi.myfirst.service.post.model.PostView;
import com.luyi.myfirst.service.post.support.FeedCursor;
import com.luyi.myfirst.service.post.support.PostInputRules;
import com.luyi.myfirst.service.post.support.PostReadAccess;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子服务实现。
 *
 * 三条写路径共享一个次序：**先判存在（404）再判作者（403）**。
 * 反过来的话，「这个标识不存在」与「存在但不是你的」会给出不同响应，
 * 等于把 identifiers 变成可枚举的信道（spec「目标不存在时的判定次序」）。
 *
 * 详情路径的可见性判定是另一套：草稿对他人返回 404 而不是 403，
 * 理由同上——403 会确认「这条草稿存在」。
 *
 * 对外一律不回传实体：DTO 装配集中在 {@link #toView} / {@link #toSummary}，
 * 白名单因此只有一个出口可以出错，也只有一个地方需要被测试盯住。
 */
@Service
public class PostServiceImpl implements PostService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final PostRepository posts;
    private final AccountRepository accounts;
    private final CommentRepository comments;
    private final VoteRepository votes;
    private final BookmarkRepository bookmarks;
    private final PostReadAccess readAccess;

    public PostServiceImpl(PostRepository posts,
                           AccountRepository accounts,
                           CommentRepository comments,
                           VoteRepository votes,
                           BookmarkRepository bookmarks,
                           PostReadAccess readAccess) {
        this.posts = posts;
        this.accounts = accounts;
        this.comments = comments;
        this.votes = votes;
        this.bookmarks = bookmarks;
        this.readAccess = readAccess;
    }

    @Override
    @Transactional
    public PostView publish(VerifiedToken author, PostCommand command) {
        PostCommand normalized = PostInputRules.normalize(command);
        Account account = author.account();
        Post post = Post.create(
                Ulid.generate(),
                account.id(),
                normalized.title(),
                normalized.content(),
                normalized.coverImage(),
                normalized.tags(),
                normalized.status(),
                Instant.now());
        return toView(posts.saveAndFlush(post), account.publicId());
    }

    @Override
    @Transactional
    public PostView edit(VerifiedToken author, String postPublicId, PostCommand command) {
        Post post = requireExisting(postPublicId);
        requireAuthor(post, author);
        PostCommand normalized = PostInputRules.normalize(command);
        post.edit(
                normalized.title(),
                normalized.content(),
                normalized.coverImage(),
                normalized.tags(),
                normalized.status(),
                Instant.now());
        // saveAndFlush 而不是 save：updated_at 由库侧 ON UPDATE 与 @Generated(UPDATE) 回读，
        // 不 flush 就映射视图，返回的会是改动前的时间。
        return toView(posts.saveAndFlush(post), author.account().publicId());
    }

    @Override
    @Transactional
    public void delete(VerifiedToken author, String postPublicId) {
        Post post = requireExisting(postPublicId);
        requireAuthor(post, author);
        post.delete(Instant.now());
        posts.saveAndFlush(post);
    }

    @Override
    @Transactional(readOnly = true)
    public PostView read(String postPublicId, VerifiedToken viewer) {
        Post post = readAccess.requireReadable(postPublicId, viewer);
        return toView(post, authorPublicId(post.authorId()));
    }

    @Override
    @Transactional(readOnly = true)
    public PostPage feed(String sortBy, String cursor, Integer limit) {
        PostSortBy resolvedSort = resolveSortBy(sortBy);
        int size = normalizeLimit(limit);
        FeedCursor after = FeedCursor.decode(cursor);
        if (after != null && after.sortBy() != resolvedSort) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }

        Pageable pageable = PageRequest.of(0, size + 1);
        List<Post> rows = fetchPage(resolvedSort, after, pageable);

        boolean hasMore = rows.size() > size;
        List<Post> page = hasMore ? rows.subList(0, size) : rows;
        Map<Long, String> authorPublicIds = authorPublicIdsOf(page);
        Map<Long, InteractionCounts> counts = countsOf(page);
        List<PostSummary> items = page.stream()
                .map(post -> toSummary(post, authorPublicIds.get(post.authorId()), counts.get(post.id())))
                .toList();

        String nextCursor = null;
        if (hasMore) {
            Post last = page.get(page.size() - 1);
            nextCursor = new FeedCursor(
                    resolvedSort,
                    last.publishedAt(),
                    scoreOf(last, resolvedSort, counts.get(last.id())),
                    last.id()).encode();
        }
        return new PostPage(items, nextCursor);
    }

    private List<Post> fetchPage(PostSortBy sortBy, FeedCursor after, Pageable pageable) {
        if (after == null) {
            return switch (sortBy) {
                case PUBLISHED_AT -> posts.findFeed(PostStatus.PUBLISHED, pageable);
                case UP_VOTE_COUNT -> posts.findFeedByUpVoteCount(PostStatus.PUBLISHED, pageable);
                case COMMENT_COUNT -> posts.findFeedByCommentCount(PostStatus.PUBLISHED, pageable);
            };
        }
        return switch (sortBy) {
            case PUBLISHED_AT -> posts.findFeedAfter(
                    PostStatus.PUBLISHED, after.publishedAt(), after.id(), pageable);
            case UP_VOTE_COUNT -> posts.findFeedByUpVoteCountAfter(
                    PostStatus.PUBLISHED, after.score(), after.publishedAt(), after.id(), pageable);
            case COMMENT_COUNT -> posts.findFeedByCommentCountAfter(
                    PostStatus.PUBLISHED, after.score(), after.publishedAt(), after.id(), pageable);
        };
    }

    private static Long scoreOf(Post post, PostSortBy sortBy, InteractionCounts counts) {
        if (counts == null) {
            return null;
        }
        return switch (sortBy) {
            case PUBLISHED_AT -> null;
            case UP_VOTE_COUNT -> counts.upVoteCount();
            case COMMENT_COUNT -> counts.commentCount();
        };
    }

    private static PostSortBy resolveSortBy(String raw) {
        if (raw == null || raw.isBlank()) {
            return PostSortBy.PUBLISHED_AT;
        }
        return PostSortBy.fromDbValue(raw).orElseThrow(() -> new BizException(AuthErrorEnum.VALIDATION_FAILED));
    }

    private Map<Long, InteractionCounts> countsOf(List<Post> page) {
        Map<Long, InteractionCounts> result = new HashMap<>();
        for (Post post : page) {
            result.put(post.id(), countsOf(post));
        }
        return result;
    }

    private InteractionCounts countsOf(Post post) {
        return new InteractionCounts(
                comments.countByPostIdAndDeletedAtIsNull(post.id()),
                votes.countByPostIdAndDirectionAndDeletedAtIsNull(post.id(), VoteDirection.UP),
                bookmarks.countByPostIdAndDeletedAtIsNull(post.id()));
    }

    private Post requireExisting(String postPublicId) {
        if (postPublicId == null || postPublicId.isBlank()) {
            throw notFound();
        }
        return posts.findByPublicIdAndDeletedAtIsNull(postPublicId).orElseThrow(PostServiceImpl::notFound);
    }

    private static void requireAuthor(Post post, VerifiedToken author) {
        if (!isAuthor(post, author)) {
            throw new BizException(PostErrorEnum.POST_FORBIDDEN);
        }
    }

    private static boolean isAuthor(Post post, VerifiedToken viewer) {
        return viewer != null && Objects.equals(post.authorId(), viewer.account().id());
    }

    private String authorPublicId(Long authorId) {
        // 外键 fk_posts_author 保证账户行存在（账户注销是软删除，行仍在），
        // 这里的 404 是防御性兜底而不是可达分支。
        return accounts.findById(authorId).map(Account::publicId).orElseThrow(PostServiceImpl::notFound);
    }

    private Map<Long, String> authorPublicIdsOf(List<Post> rows) {
        Set<Long> authorIds = rows.stream().map(Post::authorId).collect(Collectors.toSet());
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> publicIds = new HashMap<>();
        accounts.findAllById(authorIds).forEach(account -> publicIds.put(account.id(), account.publicId()));
        return publicIds;
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private PostView toView(Post post, String authorPublicId) {
        InteractionCounts counts = countsOf(post);
        return new PostView(
                post.publicId(),
                post.title(),
                post.content(),
                post.coverImage(),
                post.tags(),
                post.status(),
                authorPublicId,
                post.createdAt(),
                post.updatedAt(),
                counts.commentCount(),
                counts.upVoteCount(),
                counts.bookmarkCount());
    }

    private static PostSummary toSummary(Post post, String authorPublicId, InteractionCounts counts) {
        return new PostSummary(
                post.publicId(),
                post.title(),
                post.coverImage(),
                post.tags(),
                post.status(),
                authorPublicId,
                post.createdAt(),
                post.updatedAt(),
                counts == null ? 0L : counts.commentCount(),
                counts == null ? 0L : counts.upVoteCount(),
                counts == null ? 0L : counts.bookmarkCount());
    }

    private static BizException notFound() {
        return new BizException(PostErrorEnum.POST_NOT_FOUND);
    }

    private record InteractionCounts(long commentCount, long upVoteCount, long bookmarkCount) {
    }
}
