package com.luyi.myfirst.web.profile;

import com.luyi.myfirst.service.post.PostService;
import com.luyi.myfirst.service.post.model.PostPage;
import com.luyi.myfirst.web.post.dto.PostFeedResponse;
import com.luyi.myfirst.web.post.dto.PostSummaryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户页的帖子列表端点（GET /api/profiles/{publicId}/posts，无需登录）。
 *
 * 响应形状复用首页 feed 的 {@link PostFeedResponse}，前端因此能复用同一套条目组件
 * 与视图映射（design D5）。作者解析、可见性与分页全部在服务层，控制器不做判断。
 */
@RestController
@RequestMapping("/api/profiles")
public class UserPostsController {

    private final PostService postService;

    public UserPostsController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping("/{publicId}/posts")
    ResponseEntity<PostFeedResponse> feed(
            @PathVariable String publicId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        PostPage page = postService.feedByAuthor(publicId, cursor, limit);
        return ResponseEntity.ok(new PostFeedResponse(
                page.items().stream().map(PostSummaryResponse::from).toList(),
                page.nextCursor()));
    }
}
