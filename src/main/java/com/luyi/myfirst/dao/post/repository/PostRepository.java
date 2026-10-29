package com.luyi.myfirst.dao.post.repository;

import com.luyi.myfirst.common.enums.PostStatus;
import com.luyi.myfirst.dao.post.entity.Post;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
            order by (select count(v) from Vote v
                      where v.postId = p.id and v.direction = com.luyi.myfirst.common.enums.VoteDirection.UP and v.deletedAt is null) desc,
                     p.publishedAt desc, p.id desc
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
              and ((select count(v) from Vote v
                    where v.postId = p.id and v.direction = com.luyi.myfirst.common.enums.VoteDirection.UP and v.deletedAt is null) < :upVoteCount
                   or ((select count(v) from Vote v
                        where v.postId = p.id and v.direction = com.luyi.myfirst.common.enums.VoteDirection.UP and v.deletedAt is null) = :upVoteCount
                       and (p.publishedAt < :publishedAt
                            or (p.publishedAt = :publishedAt and p.id < :id))))
            order by (select count(v) from Vote v
                      where v.postId = p.id and v.direction = com.luyi.myfirst.common.enums.VoteDirection.UP and v.deletedAt is null) desc,
                     p.publishedAt desc, p.id desc
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
            order by (select count(c) from Comment c
                      where c.postId = p.id and c.deletedAt is null) desc,
                     p.publishedAt desc, p.id desc
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
              and ((select count(c) from Comment c
                    where c.postId = p.id and c.deletedAt is null) < :commentCount
                   or ((select count(c) from Comment c
                        where c.postId = p.id and c.deletedAt is null) = :commentCount
                       and (p.publishedAt < :publishedAt
                            or (p.publishedAt = :publishedAt and p.id < :id))))
            order by (select count(c) from Comment c
                      where c.postId = p.id and c.deletedAt is null) desc,
                     p.publishedAt desc, p.id desc
            """)
    List<Post> findFeedByCommentCountAfter(@Param("status") PostStatus status,
                                           @Param("commentCount") long commentCount,
                                           @Param("publishedAt") Instant publishedAt,
                                           @Param("id") Long id,
                                           Pageable pageable);
}
