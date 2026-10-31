package com.luyi.myfirst.web.post.interaction;

import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.post.interaction.PostInteractionService;
import com.luyi.myfirst.service.post.interaction.model.PostInteractionState;
import com.luyi.myfirst.web.auth.interceptor.AuthInterceptor;
import com.luyi.myfirst.web.post.interaction.dto.PostInteractionStateResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** 受保护的本人状态读取；查询主体只取认证拦截器确认的身份。 */
@RestController
public class PostInteractionStateController {
    private final PostInteractionService interactions;

    public PostInteractionStateController(PostInteractionService interactions) {
        this.interactions = interactions;
    }

    @GetMapping("/api/posts/{publicId}/interactions/me")
    ResponseEntity<PostInteractionStateResponse> read(
            @PathVariable String publicId,
            @RequestAttribute(AuthInterceptor.VERIFIED_ATTRIBUTE) VerifiedToken viewer) {
        PostInteractionState state = interactions.readMyInteractions(viewer, publicId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new PostInteractionStateResponse(
                        state.voteDirection() == null ? null : state.voteDirection().dbValue(),
                        state.bookmarked()));
    }
}
