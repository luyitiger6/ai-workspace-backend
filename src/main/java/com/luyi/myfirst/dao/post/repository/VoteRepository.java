package com.luyi.myfirst.dao.post.repository;

import com.luyi.myfirst.common.enums.VoteDirection;
import com.luyi.myfirst.dao.post.entity.Vote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 投票查询入口。
 *
 * 所有读方法默认过滤已软删除记录；{@code upVoteCount} 只统计 direction = up 的有效记录。
 */
public interface VoteRepository extends JpaRepository<Vote, Long> {

    Optional<Vote> findByPostIdAndAuthorIdAndDeletedAtIsNull(Long postId, Long authorId);

    boolean existsByPostIdAndDeletedAtIsNull(Long postId);

    long countByPostIdAndDirectionAndDeletedAtIsNull(Long postId, VoteDirection direction);
}
