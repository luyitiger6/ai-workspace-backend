package com.luyi.myfirst.dao.post.entity;

import com.luyi.myfirst.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 帖子收藏，每个账户对每条帖子最多一条有效记录。
 */
@Entity
@Table(name = "post_bookmarks")
public class Bookmark extends BaseEntity {

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "account_id", nullable = false)
    private Long authorId;

    protected Bookmark() {
        // JPA 需要
    }

    public static Bookmark create(String publicId,
                                  Long postId,
                                  Long authorId) {
        Bookmark bookmark = new Bookmark();
        bookmark.publicId = publicId;
        bookmark.postId = postId;
        bookmark.authorId = authorId;
        return bookmark;
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
}
