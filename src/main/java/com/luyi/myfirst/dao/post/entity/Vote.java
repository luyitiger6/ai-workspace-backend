package com.luyi.myfirst.dao.post.entity;

import com.luyi.myfirst.common.enums.VoteDirection;
import com.luyi.myfirst.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 帖子投票，每个账户对每条帖子最多一条有效记录。
 */
@Entity
@Table(name = "post_votes")
public class Vote extends BaseEntity {

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "account_id", nullable = false)
    private Long authorId;

    @Column(nullable = false, length = 8)
    private VoteDirection direction;

    protected Vote() {
        // JPA 需要
    }

    public static Vote create(String publicId,
                              Long postId,
                              Long authorId,
                              VoteDirection direction) {
        Vote vote = new Vote();
        vote.publicId = publicId;
        vote.postId = postId;
        vote.authorId = authorId;
        vote.direction = direction;
        return vote;
    }

    /** 用新方向替换旧方向（同一账户同一帖子只保留一条有效记录）。 */
    public void changeDirection(VoteDirection direction) {
        this.direction = direction;
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

    public VoteDirection direction() {
        return direction;
    }
}
