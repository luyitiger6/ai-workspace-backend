package com.luyi.myfirst.service.post.support;

import com.luyi.myfirst.common.enums.PostStatus;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.exception.PostErrorEnum;
import com.luyi.myfirst.dao.post.entity.Post;
import com.luyi.myfirst.dao.post.repository.PostRepository;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 详情与附属读取共享的可见性边界，不参与写端权限判断。 */
@Component
public class PostReadAccess {
    private final PostRepository posts;

    public PostReadAccess(PostRepository posts) {
        this.posts = posts;
    }

    public Post requireReadable(String publicId, VerifiedToken viewer) {
        if (publicId == null || publicId.isBlank()) {
            throw notFound();
        }
        Post post = posts.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(PostReadAccess::notFound);
        if (post.status() == PostStatus.DRAFT
                && (viewer == null || !Objects.equals(post.authorId(), viewer.account().id()))) {
            throw notFound();
        }
        return post;
    }

    private static BizException notFound() {
        return new BizException(PostErrorEnum.POST_NOT_FOUND);
    }
}
