package com.luyi.myfirst.dao.post.repository;

import com.luyi.myfirst.dao.post.entity.Comment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 评论查询入口。
 *
 * 所有读方法默认过滤已软删除记录，调用方不需要再判断 {@code deletedAt}。
 * 列表按创建时间倒序，分页是 keyset（{@code created_at desc, id desc}）。
 */
public interface CommentRepository extends JpaRepository<Comment, Long> {

    Optional<Comment> findByPublicIdAndDeletedAtIsNull(String publicId);

    long countByPostIdAndDeletedAtIsNull(Long postId);

    /** 评论首页：按创建时间倒序、id 倒序兜底。 */
    List<Comment> findByPostIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long postId, Pageable pageable);

    /** 游标之后的一页（createdAt 排序）。 */
    @Query("""
            select c from Comment c
            where c.postId = :postId and c.deletedAt is null
              and (c.createdAt < :createdAt
                   or (c.createdAt = :createdAt and c.id < :id))
            order by c.createdAt desc, c.id desc
            """)
    List<Comment> findByPostIdAndDeletedAtIsNullAfter(@Param("postId") Long postId,
                                                      @Param("createdAt") Instant createdAt,
                                                      @Param("id") Long id,
                                                      Pageable pageable);
}
