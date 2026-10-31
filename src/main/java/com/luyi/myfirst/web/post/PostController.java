package com.luyi.myfirst.web.post;

import com.luyi.myfirst.common.enums.PostStatus;
import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.post.PostService;
import com.luyi.myfirst.service.post.model.PostCommand;
import com.luyi.myfirst.service.post.model.PostPage;
import com.luyi.myfirst.service.post.model.PostSummary;
import com.luyi.myfirst.service.post.model.PostView;
import com.luyi.myfirst.web.auth.interceptor.AuthInterceptor;
import com.luyi.myfirst.web.post.dto.PostCreateRequest;
import com.luyi.myfirst.web.post.dto.PostFeedResponse;
import com.luyi.myfirst.web.post.dto.PostResponse;
import com.luyi.myfirst.web.post.dto.PostSummaryResponse;
import com.luyi.myfirst.web.post.dto.PostUpdateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 帖子 REST 端点。
 *
 * 控制器只做三件事：身份从 request attribute 取、DTO 与 service 模型互转、
 * 返回正确的 HTTP 状态码。业务判定（存在性、作者身份、可见性、分页）全部下沉到
 * {@link PostService}，不在这里重复。
 *
 * 身份字段 {@code verified} 对 GET 是 {@code required = false}：公开读路径上的游客
 * 不带令牌，控制器传 null 给服务层；写路径上拦截器已经保证非空，所以这里用强依赖。
 */
@RestController
@RequestMapping("/api/posts")
public class PostController {

    private static final String VERIFIED = AuthInterceptor.VERIFIED_ATTRIBUTE;

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @PostMapping
    ResponseEntity<PostResponse> create(
            @RequestAttribute(VERIFIED) VerifiedToken author,
            @RequestBody PostCreateRequest request) {
        PostView view = postService.publish(author, toCommand(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(view));
    }

    @PutMapping("/{publicId}")
    ResponseEntity<PostResponse> update(
            @RequestAttribute(VERIFIED) VerifiedToken author,
            @PathVariable String publicId,
            @RequestBody PostUpdateRequest request) {
        return ResponseEntity.ok(toResponse(postService.edit(author, publicId, toCommand(request))));
    }

    @DeleteMapping("/{publicId}")
    ResponseEntity<Void> delete(
            @RequestAttribute(VERIFIED) VerifiedToken author,
            @PathVariable String publicId) {
        postService.delete(author, publicId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{publicId}")
    ResponseEntity<PostResponse> read(
            @PathVariable String publicId,
            @RequestAttribute(name = VERIFIED, required = false) VerifiedToken viewer) {
        return ResponseEntity.ok(toResponse(postService.read(publicId, viewer)));
    }

    @GetMapping
    ResponseEntity<PostFeedResponse> feed(
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        PostPage page = postService.feed(sortBy, cursor, limit);
        return ResponseEntity.ok(new PostFeedResponse(
                page.items().stream().map(PostController::toSummary).toList(),
                page.nextCursor()));
    }

    private static PostCommand toCommand(PostCreateRequest request) {
        return new PostCommand(
                request.title(),
                request.content(),
                request.coverImage(),
                request.tags(),
                statusOf(request.status()));
    }

    private static PostCommand toCommand(PostUpdateRequest request) {
        return new PostCommand(
                request.title(),
                request.content(),
                request.coverImage(),
                request.tags(),
                statusOf(request.status()));
    }

    /** null 状态交给服务层默认 draft；非法字符串直接 400。 */
    private static PostStatus statusOf(String raw) {
        if (raw == null) {
            return null;
        }
        return PostStatus.fromDbValue(raw)
                .orElseThrow(() -> new BizException(AuthErrorEnum.VALIDATION_FAILED));
    }

    private static PostResponse toResponse(PostView view) {
        return new PostResponse(
                view.publicId(),
                view.title(),
                view.content(),
                view.coverImage(),
                view.tags(),
                view.status().dbValue(),
                view.authorPublicId(),
                view.createdAt(),
                view.updatedAt(),
                view.commentCount(),
                view.upVoteCount(),
                view.bookmarkCount());
    }

    private static PostSummaryResponse toSummary(PostSummary summary) {
        return new PostSummaryResponse(
                summary.publicId(),
                summary.title(),
                summary.coverImage(),
                summary.tags(),
                summary.status().dbValue(),
                summary.authorPublicId(),
                summary.createdAt(),
                summary.updatedAt(),
                summary.commentCount(),
                summary.upVoteCount(),
                summary.bookmarkCount());
    }
}
