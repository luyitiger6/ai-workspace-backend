package com.luyi.myfirst.web.post.interaction;

import com.luyi.myfirst.common.enums.VoteDirection;
import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
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
import com.luyi.myfirst.web.auth.interceptor.AuthInterceptor;
import com.luyi.myfirst.web.post.interaction.dto.BookmarkResponse;
import com.luyi.myfirst.web.post.interaction.dto.CommentCreateRequest;
import com.luyi.myfirst.web.post.interaction.dto.CommentListResponse;
import com.luyi.myfirst.web.post.interaction.dto.CommentResponse;
import com.luyi.myfirst.web.post.interaction.dto.VoteCreateRequest;
import com.luyi.myfirst.web.post.interaction.dto.VoteResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 帖子互动 REST 端点：评论、投票、收藏。
 *
 * 控制器只做身份从 attribute 取、DTO 与 service 命令互转、返回正确状态码三件事；
 * 存在性、作者身份等业务判定全部下沉到 {@link PostInteractionService}。
 */
@RestController
@RequestMapping("/api/posts/{postPublicId}")
public class PostInteractionController {

    private static final String VERIFIED = AuthInterceptor.VERIFIED_ATTRIBUTE;

    private final PostInteractionService interactionService;

    public PostInteractionController(PostInteractionService interactionService) {
        this.interactionService = interactionService;
    }

    @PostMapping("/comments")
    ResponseEntity<CommentResponse> createComment(
            @RequestAttribute(VERIFIED) VerifiedToken author,
            @PathVariable String postPublicId,
            @RequestBody CommentCreateRequest request) {
        CommentView view = interactionService.createComment(
                author, postPublicId, new CommentCreateCommand(request.content(), request.parentPublicId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toCommentResponse(view));
    }

    @DeleteMapping("/comments/{commentPublicId}")
    ResponseEntity<Void> deleteComment(
            @RequestAttribute(VERIFIED) VerifiedToken author,
            @PathVariable String postPublicId,
            @PathVariable String commentPublicId) {
        interactionService.deleteComment(author, postPublicId, commentPublicId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/comments")
    ResponseEntity<CommentListResponse> listComments(
            @PathVariable String postPublicId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        CommentPage page = interactionService.listComments(postPublicId, cursor, limit);
        return ResponseEntity.ok(new CommentListResponse(
                page.items().stream().map(PostInteractionController::toCommentResponse).toList(),
                page.nextCursor()));
    }

    @PostMapping("/votes")
    ResponseEntity<VoteResponse> vote(
            @RequestAttribute(VERIFIED) VerifiedToken author,
            @PathVariable String postPublicId,
            @RequestBody VoteCreateRequest request) {
        VoteDirection direction = VoteDirection.fromDbValue(request.direction())
                .orElseThrow(() -> new BizException(AuthErrorEnum.VALIDATION_FAILED));
        VoteResult result = interactionService.vote(author, postPublicId, new VoteCreateCommand(direction));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(toVoteResponse(result.view()));
    }

    @DeleteMapping("/votes")
    ResponseEntity<Void> cancelVote(
            @RequestAttribute(VERIFIED) VerifiedToken author,
            @PathVariable String postPublicId) {
        interactionService.cancelVote(author, postPublicId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bookmarks")
    ResponseEntity<BookmarkResponse> bookmark(
            @RequestAttribute(VERIFIED) VerifiedToken author,
            @PathVariable String postPublicId) {
        BookmarkResult result = interactionService.bookmark(author, postPublicId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(toBookmarkResponse(result.view()));
    }

    @DeleteMapping("/bookmarks")
    ResponseEntity<Void> cancelBookmark(
            @RequestAttribute(VERIFIED) VerifiedToken author,
            @PathVariable String postPublicId) {
        interactionService.cancelBookmark(author, postPublicId);
        return ResponseEntity.noContent().build();
    }

    private static CommentResponse toCommentResponse(CommentView view) {
        return new CommentResponse(
                view.publicId(),
                view.postPublicId(),
                view.parentPublicId(),
                view.authorPublicId(),
                view.content(),
                view.createdAt(),
                view.updatedAt());
    }

    private static VoteResponse toVoteResponse(VoteView view) {
        return new VoteResponse(
                view.publicId(),
                view.postPublicId(),
                view.authorPublicId(),
                view.direction().dbValue(),
                view.createdAt(),
                view.updatedAt());
    }

    private static BookmarkResponse toBookmarkResponse(BookmarkView view) {
        return new BookmarkResponse(
                view.publicId(),
                view.postPublicId(),
                view.authorPublicId(),
                view.createdAt(),
                view.updatedAt());
    }

}
