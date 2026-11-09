package com.luyi.myfirst.dao.dm.entity;

import com.luyi.myfirst.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一对一私信会话（design D1 / D2）。
 *
 * 一对参与者只存在一条会话：两侧账户 id 按升序归一化后写入
 * {@code participant_low_id} / {@code participant_high_id}，唯一键
 * {@code uk_dm_conversations_pair} 与 CHECK {@code participant_low_id < participant_high_id}
 * 把这条规则落到库层，而不是靠应用层记得先排序。
 *
 * 两侧各有一份未读计数与已读位置。计数让会话列表与总未读端点不需要聚合消息表；
 * 已读位置让「标记已读」按位置重算，而不是把计数直接清零——盲清零会把并发到达的新消息一并抹掉。
 * 这些列全部由仓储层的批量更新维护，实体本身不提供写入方法。
 */
@Entity
@Table(name = "dm_conversations")
public class DmConversation extends BaseEntity {

    @Column(name = "participant_low_id", nullable = false)
    private Long participantLowId;

    @Column(name = "participant_high_id", nullable = false)
    private Long participantHighId;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "low_unread_count", nullable = false)
    private int lowUnreadCount;

    @Column(name = "high_unread_count", nullable = false)
    private int highUnreadCount;

    @Column(name = "low_last_read_message_id")
    private Long lowLastReadMessageId;

    @Column(name = "high_last_read_message_id")
    private Long highLastReadMessageId;

    protected DmConversation() {
        // JPA 需要
    }

    /**
     * 建会话。调用方负责先归一化两侧 id 并把自聊挡在服务层；
     * 这里只把值写进实体，唯一键与 CHECK 是最后一道兜底。
     */
    public static DmConversation create(String publicId, Long participantLowId, Long participantHighId) {
        DmConversation conversation = new DmConversation();
        conversation.publicId = publicId;
        conversation.participantLowId = participantLowId;
        conversation.participantHighId = participantHighId;
        return conversation;
    }

    /**
     * 请求者是不是本会话的参与者。
     *
     * 非参与者一律按「会话不存在」处理（404），不区分「无权查看」——
     * 区分了就等于告诉对方「这里确实有一条你插不进去的会话」。
     */
    public boolean includes(Long accountId) {
        return participantLowId.equals(accountId) || participantHighId.equals(accountId);
    }

    /** 会话内对方的账户 id。只在 {@link #includes} 为真时调用。 */
    public Long peerOf(Long accountId) {
        return participantLowId.equals(accountId) ? participantHighId : participantLowId;
    }

    /** 请求者一侧的未读数。 */
    public int unreadCountOf(Long accountId) {
        return participantLowId.equals(accountId) ? lowUnreadCount : highUnreadCount;
    }

    public Long participantLowId() {
        return participantLowId;
    }

    public Long participantHighId() {
        return participantHighId;
    }

    public Instant lastMessageAt() {
        return lastMessageAt;
    }

    public int messageCount() {
        return messageCount;
    }

    public int lowUnreadCount() {
        return lowUnreadCount;
    }

    public int highUnreadCount() {
        return highUnreadCount;
    }

    public Long lowLastReadMessageId() {
        return lowLastReadMessageId;
    }

    public Long highLastReadMessageId() {
        return highLastReadMessageId;
    }
}
