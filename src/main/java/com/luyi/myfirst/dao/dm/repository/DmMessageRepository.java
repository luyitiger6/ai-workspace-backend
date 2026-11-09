package com.luyi.myfirst.dao.dm.repository;

import com.luyi.myfirst.dao.dm.entity.DmMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 私信消息查询入口。
 *
 * 分页拆成「首页」与「游标之后」两条 JPQL，理由与 {@code PostRepository} 一致：
 * 可空的游标参数塞进 JPQL 会引入 Hibernate 6 的空参数类型推断问题。
 *
 * 排序键是自增 {@code id} 而不是 {@code created_at}：单会话内 {@code id} 严格单调，
 * 既没有同毫秒歧义，也让 {@code idx_dm_messages_conv (conversation_id, id)} 一条索引覆盖全程。
 */
public interface DmMessageRepository extends JpaRepository<DmMessage, Long> {

    Optional<DmMessage> findByPublicId(String publicId);

    /** 首页：会话内最新的 N 条。 */
    @Query("""
            select m from DmMessage m
            where m.conversationId = :conversationId
            order by m.id desc
            """)
    List<DmMessage> findLatest(@Param("conversationId") Long conversationId, Pageable pageable);

    /** 游标之后的一页：id 小于游标的 N 条。 */
    @Query("""
            select m from DmMessage m
            where m.conversationId = :conversationId and m.id < :beforeId
            order by m.id desc
            """)
    List<DmMessage> findBefore(@Param("conversationId") Long conversationId,
                               @Param("beforeId") Long beforeId,
                               Pageable pageable);

    /** 会话内最大的消息 id；会话还没消息时返回 null。 */
    @Query("select max(m.id) from DmMessage m where m.conversationId = :conversationId")
    Long findMaxId(@Param("conversationId") Long conversationId);

    /**
     * 批量取每个会话的最后一条消息，供会话列表装配摘要。
     *
     * 一条查询解决 N+1：内层按会话分组取 max(id)，外层按 id 回表。
     * 依赖 id 全局唯一（自增主键）——同一会话内 max(id) 就是最新一条。
     */
    @Query("""
            select m from DmMessage m
            where m.id in (
                select max(m2.id) from DmMessage m2
                where m2.conversationId in :conversationIds
                group by m2.conversationId
            )
            """)
    List<DmMessage> findLatestOfEach(@Param("conversationIds") Collection<Long> conversationIds);
}
