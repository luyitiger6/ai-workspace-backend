package com.luyi.myfirst.dao.post.repository;

import com.luyi.myfirst.common.enums.PostStatus;
import com.luyi.myfirst.dao.post.entity.Post;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 帖子查询入口。
 *
 * 软删除的过滤条件写进方法名/JPQL 而不是靠调用方记得判断 {@code deletedAt}：
 * 「已删除的帖子 MUST NOT 出现在任何读接口中」是 spec 硬要求，
 * 让它成为查询的一部分，漏写一次就是查询不到，而不是悄悄返回已删除的行。
 *
 * 分页是 keyset（design D8/D4）：排序键由 {@code sortBy} 决定，
 * 游标由服务层编码成不透明串，这里只接受解出来的键值组。
 * 拆成「首页」与「游标之后」两条查询，是为了不把可空的游标参数塞进 JPQL——
 * 空参数的隐式类型推断是 Hibernate 6 里一类难查的隐患。
 */
public interface PostRepository extends JpaRepository<Post, Long> {

    Optional<Post> findByPublicIdAndDeletedAtIsNull(String publicId);

    /** 作者的帖子（含草稿）。本阶段只有测试用到，产品侧没有「我的帖子」入口。 */
    List<Post> findAllByAuthorIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long authorId);

    /** feed 首页：按发布时刻倒序、id 倒序兜底。 */
    @Query("""
            select p from Post p
            where p.status = :status and p.deletedAt is null
            order by p.publishedAt desc, p.id desc
            """)
    List<Post> findFeed(@Param("status") PostStatus status, Pageable pageable);

    /**
     * 游标之后的一页（publishedAt 排序）。
     *
     * 条件是 {@code (published_at, id) < (:publishedAt, :id)} 的字典序比较：
     * 只用 id 做游标不行（排序键是 published_at），只用 published_at 会漏掉
     * 同一毫秒发布的其余帖子——同秒发布正是测试必须覆盖的边界。
     */
    @Query("""
            select p from Post p
            where p.status = :status and p.deletedAt is null
              and (p.publishedAt < :publishedAt
                   or (p.publishedAt = :publishedAt and p.id < :id))
            order by p.publishedAt desc, p.id desc
            """)
    List<Post> findFeedAfter(@Param("status") PostStatus status,
                             @Param("publishedAt") Instant publishedAt,
                             @Param("id") Long id,
                             Pageable pageable);

    /** feed 首页：按 upVoteCount 倒序、publishedAt 倒序、id 倒序兜底。 */
    @Query("""
            select p from Post p
            where p.status = :status and p.deletedAt is null
            order by p.upVoteCount desc, p.publishedAt desc, p.id desc
            """)
    List<Post> findFeedByUpVoteCount(@Param("status") PostStatus status, Pageable pageable);

    /**
     * 游标之后的一页（upVoteCount 排序）。
     *
     * 条件是 {@code (up_vote_count, published_at, id) < (:upVoteCount, :publishedAt, :id)}。
     */
    @Query("""
            select p from Post p
            where p.status = :status and p.deletedAt is null
              and (p.upVoteCount < :upVoteCount
                   or (p.upVoteCount = :upVoteCount
                       and (p.publishedAt < :publishedAt
                            or (p.publishedAt = :publishedAt and p.id < :id))))
            order by p.upVoteCount desc, p.publishedAt desc, p.id desc
            """)
    List<Post> findFeedByUpVoteCountAfter(@Param("status") PostStatus status,
                                          @Param("upVoteCount") long upVoteCount,
                                          @Param("publishedAt") Instant publishedAt,
                                          @Param("id") Long id,
                                          Pageable pageable);

    /** feed 首页：按 commentCount 倒序、publishedAt 倒序、id 倒序兜底。 */
    @Query("""
            select p from Post p
            where p.status = :status and p.deletedAt is null
            order by p.commentCount desc, p.publishedAt desc, p.id desc
            """)
    List<Post> findFeedByCommentCount(@Param("status") PostStatus status, Pageable pageable);

    /**
     * 游标之后的一页（commentCount 排序）。
     *
     * 条件是 {@code (comment_count, published_at, id) < (:commentCount, :publishedAt, :id)}。
     */
    @Query("""
            select p from Post p
            where p.status = :status and p.deletedAt is null
              and (p.commentCount < :commentCount
                   or (p.commentCount = :commentCount
                       and (p.publishedAt < :publishedAt
                            or (p.publishedAt = :publishedAt and p.id < :id))))
            order by p.commentCount desc, p.publishedAt desc, p.id desc
            """)
    List<Post> findFeedByCommentCountAfter(@Param("status") PostStatus status,
                                           @Param("commentCount") long commentCount,
                                           @Param("publishedAt") Instant publishedAt,
                                           @Param("id") Long id,
                                           Pageable pageable);

    /**
     * 三个计数列的原子增量。
     *
     * 写成 {@code 列 = 列 + :delta} 而不是「读出来加一再写回」：后者在并发下会丢更新。
     * {@code updated_at = updated_at} 是必要的：列定义带 ON UPDATE CURRENT_TIMESTAMP(3)，
     * 不显式保持，一次点赞就会把帖子的更新时间改成现在。
     * 原生 SQL 是刻意的：JPQL bulk update 无法可靠地表达「列引用自身列」。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update posts set up_vote_count = up_vote_count + :delta, updated_at = updated_at "
            + "where id = :id and deleted_at is null", nativeQuery = true)
    int incrementUpVoteCount(@Param("id") Long id, @Param("delta") int delta);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update posts set comment_count = comment_count + :delta, updated_at = updated_at "
            + "where id = :id and deleted_at is null", nativeQuery = true)
    int incrementCommentCount(@Param("id") Long id, @Param("delta") int delta);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update posts set bookmark_count = bookmark_count + :delta, updated_at = updated_at "
            + "where id = :id and deleted_at is null", nativeQuery = true)
    int incrementBookmarkCount(@Param("id") Long id, @Param("delta") int delta);
}
