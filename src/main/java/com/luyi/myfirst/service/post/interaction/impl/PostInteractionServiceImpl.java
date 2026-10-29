package com.luyi.myfirst.service.post.interaction.impl;

import com.luyi.myfirst.common.enums.VoteDirection;
import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.exception.PostErrorEnum;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.post.entity.Bookmark;
import com.luyi.myfirst.dao.post.entity.Comment;
import com.luyi.myfirst.dao.post.entity.Post;
import com.luyi.myfirst.dao.post.entity.Vote;
import com.luyi.myfirst.dao.post.repository.BookmarkRepository;
import com.luyi.myfirst.dao.post.repository.CommentRepository;
import com.luyi.myfirst.dao.post.repository.PostRepository;
import com.luyi.myfirst.dao.post.repository.VoteRepository;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.post.interaction.PostInteractionService;
import com.luyi.myfirst.service.post.interaction.model.BookmarkResult;
import com.luyi.myfirst.service.post.interaction.model.BookmarkView;
import com.luyi.myfirst.service.post.interaction.model.CommentCreateCommand;
import com.luyi.myfirst.service.post.interaction.model.CommentPage;
import com.luyi.myfirst.service.post.interaction.model.CommentView;
import com.luyi.myfirst.service.post.interaction.model.VoteCreateCommand;
import com.luyi.myfirst.service.post.interaction.model.VoteResult;
import com.luyi.myfirst.service.post.interaction.model.VoteView;
import com.luyi.myfirst.service.post.interaction.support.CommentCursor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子互动服务实现。
 *
 * 写路径统一遵循「先判帖子存在（404），再判资源存在（404），再判作者/投票人/收藏人（403）」。
 */
@Service
public class PostInteractionServiceImpl implements PostInteractionService {

    private static final int DEFAULT_COMMENT_LIMIT = 20;
    private static final int MAX_COMMENT_LIMIT = 50;
    private static final int MAX_COMMENT_LENGTH = 2000;

    private final PostRepository posts;
    private final AccountRepository accounts;
    private final CommentRepository comments;
    private final VoteRepository votes;
    private final BookmarkRepository bookmarks;

    public PostInteractionServiceImpl(PostRepository posts,
                                      AccountRepository accounts,
                                      CommentRepository comments,
                                      VoteRepository votes,
                                      BookmarkRepository bookmarks) {
        this.posts = posts;
        this.accounts = accounts;
        this.comments = comments;
        this.votes = votes;
        this.bookmarks = bookmarks;
    }

    @Override
    @Transactional
    public CommentView createComment(VerifiedToken author, String postPublicId, CommentCreateCommand command) {
        Post post = requireExistingPost(postPublicId);
        String content = validateCommentContent(command.content());
        Long parentId = resolveParentId(post.id(), command.parentPublicId());
        Comment comment = Comment.create(Ulid.generate(), post.id(), author.account().id(), parentId, content);
        return toCommentView(comments.saveAndFlush(comment), post.publicId(), author.account().publicId(), command.parentPublicId());
    }

    @Override
    @Transactional
    public void deleteComment(VerifiedToken author, String postPublicId, String commentPublicId) {
        Post post = requireExistingPost(postPublicId);
        Comment comment = requireExistingComment(commentPublicId);
        if (!Objects.equals(comment.postId(), post.id())) {
            throw notFound();
        }
        requireAuthor(comment.authorId(), author);
        comment.delete(Instant.now());
        comments.saveAndFlush(comment);
    }

    @Override
    @Transactional(readOnly = true)
    public CommentPage listComments(String postPublicId, String cursor, Integer limit) {
        Post post = requireExistingPost(postPublicId);
        int size = normalizeCommentLimit(limit);
        CommentCursor after = CommentCursor.decode(cursor);
        Pageable pageable = PageRequest.of(0, size + 1);
        List<Comment> rows = after == null
                ? comments.findByPostIdAndDeletedAtIsNullOrderByCreatedAtDesc(post.id(), pageable)
                : comments.findByPostIdAndDeletedAtIsNullAfter(post.id(), after.createdAt(), after.id(), pageable);

        boolean hasMore = rows.size() > size;
        List<Comment> page = hasMore ? rows.subList(0, size) : rows;
        Map<Long, String> authorPublicIds = authorPublicIdsOf(page.stream().map(Comment::authorId).collect(Collectors.toSet()));
        Map<Long, String> parentPublicIds = page.stream()
                .filter(c -> c.parentId() != null)
                .collect(Collectors.toMap(Comment::parentId, Comment::publicId, (a, b) -> a));

        List<CommentView> items = page.stream()
                .map(c -> toCommentView(c, post.publicId(),
                        authorPublicIds.getOrDefault(c.authorId(), "?"),
                        parentPublicIds.get(c.parentId())))
                .toList();

        String nextCursor = null;
        if (hasMore) {
            Comment last = page.get(page.size() - 1);
            nextCursor = new CommentCursor(last.createdAt(), last.id()).encode();
        }
        return new CommentPage(items, nextCursor);
    }

    @Override
    @Transactional
    public VoteResult vote(VerifiedToken author, String postPublicId, VoteCreateCommand command) {
        Post post = requireExistingPost(postPublicId);
        VoteDirection direction = requireDirection(command.direction());
        Optional<Vote> existing = votes.findByPostIdAndAuthorIdAndDeletedAtIsNull(post.id(), author.account().id());
        if (existing.isPresent()) {
            Vote vote = existing.get();
            vote.changeDirection(direction);
            return new VoteResult(false, toVoteView(votes.saveAndFlush(vote), post.publicId(), author.account().publicId()));
        }
        Vote vote = Vote.create(Ulid.generate(), post.id(), author.account().id(), direction);
        return new VoteResult(true, toVoteView(votes.saveAndFlush(vote), post.publicId(), author.account().publicId()));
    }

    @Override
    @Transactional
    public void cancelVote(VerifiedToken author, String postPublicId) {
        Post post = requireExistingPost(postPublicId);
        Optional<Vote> own = votes.findByPostIdAndAuthorIdAndDeletedAtIsNull(post.id(), author.account().id());
        if (own.isPresent()) {
            own.get().delete(Instant.now());
            votes.saveAndFlush(own.get());
            return;
        }
        // 帖子存在投票但不是该用户投的：判 403；没有任何投票才判 404。
        if (votes.existsByPostIdAndDeletedAtIsNull(post.id())) {
            throw new BizException(PostErrorEnum.POST_FORBIDDEN);
        }
        throw notFound();
    }

    @Override
    @Transactional
    public BookmarkResult bookmark(VerifiedToken author, String postPublicId) {
        Post post = requireExistingPost(postPublicId);
        Optional<Bookmark> existing = bookmarks.findByPostIdAndAuthorIdAndDeletedAtIsNull(post.id(), author.account().id());
        if (existing.isPresent()) {
            return new BookmarkResult(false, toBookmarkView(existing.get(), post.publicId(), author.account().publicId()));
        }
        Bookmark bookmark = Bookmark.create(Ulid.generate(), post.id(), author.account().id());
        return new BookmarkResult(true, toBookmarkView(bookmarks.saveAndFlush(bookmark), post.publicId(), author.account().publicId()));
    }

    @Override
    @Transactional
    public void cancelBookmark(VerifiedToken author, String postPublicId) {
        Post post = requireExistingPost(postPublicId);
        Optional<Bookmark> own = bookmarks.findByPostIdAndAuthorIdAndDeletedAtIsNull(post.id(), author.account().id());
        if (own.isPresent()) {
            own.get().delete(Instant.now());
            bookmarks.saveAndFlush(own.get());
            return;
        }
        // 帖子存在收藏但不是该用户收藏的：判 403；没有任何收藏才判 404。
        if (bookmarks.existsByPostIdAndDeletedAtIsNull(post.id())) {
            throw new BizException(PostErrorEnum.POST_FORBIDDEN);
        }
        throw notFound();
    }

    private Post requireExistingPost(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            throw notFound();
        }
        return posts.findByPublicIdAndDeletedAtIsNull(publicId).orElseThrow(PostInteractionServiceImpl::notFound);
    }

    private Comment requireExistingComment(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            throw notFound();
        }
        return comments.findByPublicIdAndDeletedAtIsNull(publicId).orElseThrow(PostInteractionServiceImpl::notFound);
    }

    private static int normalizeCommentLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_COMMENT_LIMIT;
        }
        return Math.min(limit, MAX_COMMENT_LIMIT);
    }

    private static String validateCommentContent(String raw) {
        if (raw == null) {
            throw validationFailed();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_COMMENT_LENGTH) {
            throw validationFailed();
        }
        return trimmed;
    }

    private Long resolveParentId(Long postId, String parentPublicId) {
        if (parentPublicId == null || parentPublicId.isBlank()) {
            return null;
        }
        Comment parent = requireExistingComment(parentPublicId);
        if (!Objects.equals(parent.postId(), postId)) {
            throw notFound();
        }
        return parent.id();
    }

    private static VoteDirection requireDirection(VoteDirection direction) {
        if (direction == null) {
            throw validationFailed();
        }
        return direction;
    }

    private static void requireAuthor(Long authorId, VerifiedToken caller) {
        if (!Objects.equals(authorId, caller.account().id())) {
            throw new BizException(PostErrorEnum.POST_FORBIDDEN);
        }
    }

    private Map<Long, String> authorPublicIdsOf(Set<Long> authorIds) {
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        accounts.findAllById(authorIds).forEach(account -> result.put(account.id(), account.publicId()));
        return result;
    }

    private static CommentView toCommentView(Comment comment, String postPublicId, String authorPublicId, String parentPublicId) {
        return new CommentView(
                comment.publicId(),
                postPublicId,
                parentPublicId,
                authorPublicId,
                comment.content(),
                comment.createdAt(),
                comment.updatedAt());
    }

    private static VoteView toVoteView(Vote vote, String postPublicId, String authorPublicId) {
        return new VoteView(
                vote.publicId(),
                postPublicId,
                authorPublicId,
                vote.direction(),
                vote.createdAt(),
                vote.updatedAt());
    }

    private static BookmarkView toBookmarkView(Bookmark bookmark, String postPublicId, String authorPublicId) {
        return new BookmarkView(
                bookmark.publicId(),
                postPublicId,
                authorPublicId,
                bookmark.createdAt(),
                bookmark.updatedAt());
    }

    private static BizException notFound() {
        return new BizException(PostErrorEnum.POST_NOT_FOUND);
    }

    private static BizException validationFailed() {
        return new BizException(AuthErrorEnum.VALIDATION_FAILED);
    }
}
