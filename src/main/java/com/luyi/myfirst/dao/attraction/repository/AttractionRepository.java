package com.luyi.myfirst.dao.attraction.repository;

import com.luyi.myfirst.common.enums.AttractionStatus;
import com.luyi.myfirst.dao.attraction.entity.Attraction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 景点查询入口。
 *
 * 三个可见性条件——{@code status = published}、{@code deleted_at IS NULL}、
 * {@code verified_at IS NOT NULL}——写进每一条查询而不是靠调用方记得过滤：
 * 「草稿、已删除、未核实的景点 MUST NOT 出现在任何读接口中」是 spec 硬要求，
 * 让它成为查询的一部分，漏写一次就是查不到，而不是悄悄返回不该出现的行。
 *
 * 三种排序 ×（全量 / 单城）各一条，与 {@code PostRepository} 同一取舍：
 * 宁可近乎重复，也不把可空的 {@code city} 塞进 JPQL 再用
 * {@code (:city IS NULL OR a.citySlug = :city)} 绕——空参数的隐式类型推断
 * 是 Hibernate 6 里一类难查的隐患，而这里的重复只有六份、每份五条短条件。
 *
 * 榜单查询不带分页：三个榜单是人工策展的有限集合（种子规模为个位数），
 * 取全量后由服务层按 {@code limit} 截断，这样的 {@code total} 才是榜单成员数，
 * 而不是「这一页有几条」。
 */
public interface AttractionRepository extends JpaRepository<Attraction, Long> {

    /**
     * 详情：只有已发布、未删除、已核实的景点才算存在。
     *
     * 草稿与未核实走同一出口返回 404：如果对草稿返回另一种状态码，
     * 等于确认「这个标识存在但你没权限看」。
     */
    Optional<Attraction> findByPublicIdAndStatusAndVerifiedAtIsNotNullAndDeletedAtIsNull(
            String publicId, AttractionStatus status);

    /** 全量列表：浏览数降序，并列时按 {@code publicId} 升序（spec 的稳定排序要求）。 */
    @Query("""
            select a from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
            order by a.viewCount desc, a.publicId asc
            """)
    List<Attraction> findHot(@Param("status") AttractionStatus status, Pageable pageable);

    /** 单城列表：浏览数降序，并列时按 {@code publicId} 升序。 */
    @Query("""
            select a from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
              and a.citySlug = :citySlug
            order by a.viewCount desc, a.publicId asc
            """)
    List<Attraction> findHotByCity(@Param("status") AttractionStatus status,
                                  @Param("citySlug") String citySlug,
                                  Pageable pageable);

    /**
     * 全量列表：评分降序。
     *
     * 评分为 NULL（未知）的景点排在最后——MySQL 的 {@code DESC} 把 NULL 置于末尾，
     * 与「未知不应该冒充高分」的口径一致，不需要额外写 {@code NULLS LAST}。
     */
    @Query("""
            select a from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
            order by a.rating desc, a.publicId asc
            """)
    List<Attraction> findRated(@Param("status") AttractionStatus status, Pageable pageable);

    /** 单城列表：评分降序。 */
    @Query("""
            select a from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
              and a.citySlug = :citySlug
            order by a.rating desc, a.publicId asc
            """)
    List<Attraction> findRatedByCity(@Param("status") AttractionStatus status,
                                     @Param("citySlug") String citySlug,
                                     Pageable pageable);

    /** 全量列表：发布时刻降序。 */
    @Query("""
            select a from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
            order by a.publishedAt desc, a.publicId asc
            """)
    List<Attraction> findNewest(@Param("status") AttractionStatus status, Pageable pageable);

    /** 单城列表：发布时刻降序。 */
    @Query("""
            select a from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
              and a.citySlug = :citySlug
            order by a.publishedAt desc, a.publicId asc
            """)
    List<Attraction> findNewestByCity(@Param("status") AttractionStatus status,
                                      @Param("citySlug") String citySlug,
                                      Pageable pageable);

    /**
     * 全量 `first-trip` 榜：只取被策展收录的成员，顺序等于策展顺序。
     *
     * 末尾的 {@code a.id} 是并列兜底：库侧没有对 {@code (榜单列)} 建唯一约束，
     * 两个成员被录入同一个名次时仍要有确定顺序，否则每次执行的返回可能不同。
     */
    @Query("""
            select a from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
              and a.curatedRankFirstTrip is not null
            order by a.curatedRankFirstTrip asc, a.id asc
            """)
    List<Attraction> findCuratedFirstTrip(@Param("status") AttractionStatus status);

    /** 单城 `first-trip` 榜：顺序等于策展顺序。 */
    @Query("""
            select a from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
              and a.curatedRankFirstTrip is not null
              and a.citySlug = :citySlug
            order by a.curatedRankFirstTrip asc, a.id asc
            """)
    List<Attraction> findCuratedFirstTripByCity(@Param("status") AttractionStatus status,
                                                @Param("citySlug") String citySlug);

    /** 全量 `no-booking` 榜：顺序等于策展顺序。 */
    @Query("""
            select a from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
              and a.curatedRankNoBooking is not null
            order by a.curatedRankNoBooking asc, a.id asc
            """)
    List<Attraction> findCuratedNoBooking(@Param("status") AttractionStatus status);

    /** 单城 `no-booking` 榜：顺序等于策展顺序。 */
    @Query("""
            select a from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
              and a.curatedRankNoBooking is not null
              and a.citySlug = :citySlug
            order by a.curatedRankNoBooking asc, a.id asc
            """)
    List<Attraction> findCuratedNoBookingByCity(@Param("status") AttractionStatus status,
                                                @Param("citySlug") String citySlug);

    /** 全量 `open-monday` 榜：顺序等于策展顺序。 */
    @Query("""
            select a from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
              and a.curatedRankOpenMonday is not null
            order by a.curatedRankOpenMonday asc, a.id asc
            """)
    List<Attraction> findCuratedOpenMonday(@Param("status") AttractionStatus status);

    /** 单城 `open-monday` 榜：顺序等于策展顺序。 */
    @Query("""
            select a from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
              and a.curatedRankOpenMonday is not null
              and a.citySlug = :citySlug
            order by a.curatedRankOpenMonday asc, a.id asc
            """)
    List<Attraction> findCuratedOpenMondayByCity(@Param("status") AttractionStatus status,
                                                 @Param("citySlug") String citySlug);

    /** 全量列表的 {@code total}：与 {@link #findHot} 同一组条件，只是不取行。 */
    @Query("""
            select count(a) from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
            """)
    long countVisible(@Param("status") AttractionStatus status);

    /** 单城列表的 {@code total}。 */
    @Query("""
            select count(a) from Attraction a
            where a.status = :status and a.deletedAt is null and a.verifiedAt is not null
              and a.citySlug = :citySlug
            """)
    long countVisibleByCity(@Param("status") AttractionStatus status,
                            @Param("citySlug") String citySlug);
}