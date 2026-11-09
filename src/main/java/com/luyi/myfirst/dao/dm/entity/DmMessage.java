package com.luyi.myfirst.dao.dm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * 私信消息。追加型表，不继承 {@code BaseEntity}。
 *
 * 消息不可编辑、不可删除，也没有软删与乐观锁的写入方：帖子里那套
 * {@code deleted_at} / {@code version} / {@code updated_by} 在这里只会成为没人维护的装饰。
 * 只保留 {@code id} / {@code public_id} / {@code created_at} 三列与四个业务列（design D10）。
 *
 * 会话内排序按自增 {@code id}，不用 {@code created_at}：
 * {@code id} 在同一会话内严格单调，天然没有同毫秒歧义，也让
 * {@code idx_dm_messages_conv (conversation_id, id)} 一条索引同时服务排序与游标。
 */
@Entity
@Table(name = "dm_messages")
public class DmMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外标识：ULID（Crockford Base32，26 字符）。库里 CHAR(26) ascii_bin。 */
    @Column(name = "public_id", nullable = false, length = 26)
    private String publicId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(nullable = false, length = 2000)
    private String content;

    /** 库侧默认值填充；{@code @Generated(INSERT)} 让插入后能读回真实落库时间。 */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected DmMessage() {
        // JPA 需要
    }

    public static DmMessage create(String publicId, Long conversationId, Long senderId, String content) {
        DmMessage message = new DmMessage();
        message.publicId = publicId;
        message.conversationId = conversationId;
        message.senderId = senderId;
        message.content = content;
        return message;
    }

    public Long id() {
        return id;
    }

    public String publicId() {
        return publicId;
    }

    public Long conversationId() {
        return conversationId;
    }

    public Long senderId() {
        return senderId;
    }

    public String content() {
        return content;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
