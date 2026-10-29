package com.luyi.myfirst.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * 业务实体表的公共八列（{@code docs/db-design-spec.md} §4.1，design D1）。
 *
 * 字段用 {@code protected} 而不是 {@code private}：子类要能直接映射自己的业务列，
 * 而公共列由本类统一映射，避免每张新表把 {@code @Version} / {@code @Generated(INSERT)}
 * 这类容易写错的注解再抄一遍。访问器沿用项目既有风格：具名、不带 {@code get} 前缀。
 *
 * 为什么只有一个子类（{@code Post}）：把 {@code Account} / {@code AuthSession} 也挪到
 * 这棵树下，等于在已归档能力上做一等公民改造，回归面覆盖整条认证链路，收益只是「风格统一」。
 * 见 design D1 的取舍。
 *
 * {@code created_by} / {@code updated_by} 按 §4.1 保留映射，本阶段没有写入方（留 NULL）：
 * 作者归属由业务列表达，再写一份审计主键只会产生「两个字段谁为准」的歧义。
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Long id;

    /** 对外标识：ULID（Crockford Base32，26 字符）。库里 CHAR(26) ascii_bin。 */
    @Column(name = "public_id", nullable = false, length = 26)
    protected String publicId;

    /**
     * 库侧默认值填充，Java 端只读。
     *
     * {@code @Generated(INSERT)} 让 Hibernate 在插入后把该列读回来：不读的话，
     * 刚创建的实体在同一个事务里读到的就是 null，响应里的创建时间为空。
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    protected Instant createdAt;

    /**
     * 与 {@code created_at} 同一机制，但**多挂一个 UPDATE 事件**。
     *
     * 列定义是 {@code ON UPDATE CURRENT_TIMESTAMP(3)}，而 {@code updated_at} 在响应白名单里：
     * 只挂 INSERT 的话，编辑接口返回的仍是插入时那一刻的时间，看起来「没保存」。
     * 挂上 UPDATE 后 Hibernate 在每次 update 后重读该列，返回的就是库侧真实值。
     */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    protected Instant updatedAt;

    /** 软删除时间；NULL = 有效。删除是写这个列，不是 DELETE 行。 */
    @Column(name = "deleted_at")
    protected Instant deletedAt;

    /** 乐观锁列；schema.sql 有 DEFAULT 0 与非负 CHECK。 */
    @Version
    @Column(nullable = false)
    protected int version;

    @Column(name = "created_by")
    protected Long createdBy;

    @Column(name = "updated_by")
    protected Long updatedBy;

    protected BaseEntity() {
        // JPA 需要
    }

    public Long id() {
        return id;
    }

    public String publicId() {
        return publicId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant deletedAt() {
        return deletedAt;
    }

    public int version() {
        return version;
    }

    public Long createdBy() {
        return createdBy;
    }

    public Long updatedBy() {
        return updatedBy;
    }

    /** 是否已软删除。读接口一律以它为准，不自行判断 {@code deletedAt != null}。 */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** 软删除的唯一入口：只写时间，不删行（spec「删除是软删除」）。 */
    protected void markDeleted(Instant occurredAt) {
        this.deletedAt = occurredAt;
    }
}
