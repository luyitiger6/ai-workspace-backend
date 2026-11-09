package com.luyi.myfirst.dao.dm.repository;

import com.luyi.myfirst.dao.dm.entity.DmConversation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 私信会话的读写入口。
 *
 * 读路径只碰计数列，不做消息表聚合（design D2）；写路径全部是批量更新，
 * 不把计数读进 Java 再加一写回——那样并发 +1 会互相覆盖。
 *
 * {@code updated_at = updated_at} 是显式让它保持不变：这些更新只动计数与摘要，
 * 业务上的「最后活跃时间」已经有 {@code last_message_at} 表达，不需要再刷新审计列。
 */
public interface DmConversationRepository extends JpaRepository<DmConversation, Long> {

    Optional<DmConversation> findByPublicIdAndDeletedAtIsNull(String publicId);

    Optional<DmConversation> findByParticipantLowIdAndParticipantHighIdAndDeletedAtIsNull(
            Long participantLowId, Long participantHighId);

    /**
     * 建会话，并发重复发起不产生第二条。
     *
     * {@code ON DUPLICATE KEY UPDATE} 把「先查后插」压成一条原子语句：撞到
     * {@code uk_dm_conversations_pair} 时不抛异常，只是把该对的行留着，调用方随后
     * 按 (low, high) 回查即可。不靠捕获唯一键异常——那会把当前事务标记为 rollback-only，
     * 回查反而查不到东西。冲突分支只做 {@code participant_low_id = participant_low_id}，
     * 值未变，不会刷新 {@code updated_at}。
     *
     * 创建时就把 {@code last_message_at} 填成当前时刻：spec 要求「尚无消息的会话按其创建
     * 时间参与同一排序」，而 {@code ORDER BY last_message_at DESC} 会把 NULL 沉底。
     * 这个值只用于排序；对外的最后消息时间仍取最后一条消息的 {@code created_at}。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "insert into dm_conversations "
            + "(public_id, participant_low_id, participant_high_id, last_message_at) "
            + "values (:publicId, :lowId, :highId, :createdAt) "
            + "on duplicate key update participant_low_id = participant_low_id", nativeQuery = true)
    int insertIfAbsent(@Param("publicId") String publicId,
                       @Param("lowId") long lowId,
                       @Param("highId") long highId,
                       @Param("createdAt") Instant createdAt);

    /**
     * 我参与的全部会话，按最后消息时间倒序、id 倒序兜底。
     *
     * {@code last_message_at} 在建会话时就已填成创建时刻（见 {@link #insertIfAbsent}），
     * 所以尚无消息的会话按创建时间参与同一排序，不会被 NULL 沉底。
     */
    @Query("""
            select c from DmConversation c
            where c.deletedAt is null
              and (c.participantLowId = :accountId or c.participantHighId = :accountId)
            order by c.lastMessageAt desc, c.id desc
            """)
    List<DmConversation> findByParticipant(@Param("accountId") Long accountId, Pageable pageable);

    /** 总未读：请求者自己那一侧的计数求和。 */
    @Query("""
            select coalesce(sum(case when c.participantLowId = :accountId
                                     then c.lowUnreadCount else c.highUnreadCount end), 0)
            from DmConversation c
            where c.deletedAt is null
              and (c.participantLowId = :accountId or c.participantHighId = :accountId)
            """)
    long sumUnread(@Param("accountId") Long accountId);

    /**
     * 发送消息后的摘要更新：最后消息时间、消息总数、对方未读，三处一次原子增量。
     *
     * 用 {@code IF(participant_low_id = :senderId, 0, 1)} 判断哪一侧是接收方，
     * 避免服务层多一次分支查询，也避免拆成两条 update 两次加行锁。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update dm_conversations set "
            + "last_message_at = :sentAt, "
            + "message_count = message_count + 1, "
            + "low_unread_count = low_unread_count + if(participant_low_id = :senderId, 0, 1), "
            + "high_unread_count = high_unread_count + if(participant_high_id = :senderId, 0, 1), "
            + "updated_at = updated_at "
            + "where id = :id and deleted_at is null", nativeQuery = true)
    int applyNewMessage(@Param("id") Long id, @Param("senderId") Long senderId, @Param("sentAt") Instant sentAt);

    /**
     * 低侧参与者标记已读到 {@code messageId}：位置推进 + 未读**按位置重算**。
     *
     * 为什么不清零：服务层查 maxId 与本条 update 之间可能刚落库一条新消息，
     * 清零会把没人看到的那条一起抹掉。重算的结果只取决于「位置之后还有几条」，
     * 与「什么时候执行清零」无关。
     *
     * {@code messageId} 由调用方保证非空（空会话用 0）。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update dm_conversations set "
            + "low_last_read_message_id = :messageId, "
            + "low_unread_count = (select count(*) from dm_messages m "
            + "  where m.conversation_id = :conversationId "
            + "    and m.sender_id <> :accountId "
            + "    and m.id > :messageId), "
            + "updated_at = updated_at "
            + "where id = :conversationId and deleted_at is null", nativeQuery = true)
    int markReadForLow(@Param("conversationId") Long conversationId,
                       @Param("accountId") Long accountId,
                       @Param("messageId") long messageId);

    /** 高侧参与者标记已读，语义与 {@link #markReadForLow} 相同。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update dm_conversations set "
            + "high_last_read_message_id = :messageId, "
            + "high_unread_count = (select count(*) from dm_messages m "
            + "  where m.conversation_id = :conversationId "
            + "    and m.sender_id <> :accountId "
            + "    and m.id > :messageId), "
            + "updated_at = updated_at "
            + "where id = :conversationId and deleted_at is null", nativeQuery = true)
    int markReadForHigh(@Param("conversationId") Long conversationId,
                        @Param("accountId") Long accountId,
                        @Param("messageId") long messageId);
}
