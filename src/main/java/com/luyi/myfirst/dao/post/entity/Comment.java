package com.luyi.myfirst.dao.post.entity;

import com.luyi.myfirst.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 帖子评论，支持二级回复（parentId）。
 */
@Entity
@Table(name = "post_comments")
public class Comment extends BaseEntity {

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "account_id", nullable = false)
    private Long authorId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 2000)
    private String content;

    protected Comment() {
        // JPA 需要
    }

    public static Comment create(String publicId,
                                  Long postId,
                                  Long authorId,
                                  Long parentId,
                                  String content) {
        Comment comment = new Comment();
        comment.publicId = publicId;
        comment.postId = postId;
        comment.authorId = authorId;
        comment.parentId = parentId;
        comment.content = content;
        return comment;
    }

    /** 软删除：只写时间，行仍在。 */
    public void delete(java.time.Instant now) {
        markDeleted(now);
    }

    public Long postId() {
        return postId;
    }

    public Long authorId() {
        return authorId;
    }

    public Long parentId() {
        return parentId;
    }

    public String content() {
        return content;
    }
}
