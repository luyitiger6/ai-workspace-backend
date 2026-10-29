package com.luyi.myfirst.dao.post.entity;

import com.luyi.myfirst.common.enums.PostStatus;
import com.luyi.myfirst.common.persistence.BaseEntity;
import com.luyi.myfirst.dao.post.converter.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/**
 * 帖子主体。
 *
 * 公共八列继承自 {@link BaseEntity}；这里只有业务列与两条具名写路径
 * （{@link #edit} / {@link #delete}）——没有 setter：状态与发布时间的联动
 * 一旦散在调用方，就会有「改成 published 但忘了写 published_at」的行。
 *
 * 作者只存 {@code author_id}（内部主键），对外一律换成 {@code publicId}：
 * spec 要求响应里不出现自增主键，这个转换在服务层的 DTO 装配处完成。
 */
@Entity
@Table(name = "posts")
public class Post extends BaseEntity {

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(nullable = false, length = 120)
    private String title;

    /** MEDIUMTEXT：正文不是元数据；应用层另按 spec 卡 65535 字节。 */
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "cover_image", length = 512)
    private String coverImage;

    /** JSON 列；本阶段不做按标签筛选（design D5 / §8.1），只读写整列。 */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "tags")
    private List<String> tags;

    @Column(nullable = false, length = 16)
    private PostStatus status;

    /**
     * 发布时刻，不是创建时刻。
     *
     * 先用 {@code created_at} 排序会把「三天前存草稿、今天发布」的帖子排到老帖前面。
     * 回到草稿时置空，重新发布时按新的发布时刻写。
     */
    @Column(name = "published_at")
    private Instant publishedAt;

    protected Post() {
        // JPA 需要
    }

    /**
     * 建立一条帖子行。
     *
     * {@code publicId} 由调用方生成并传入：对外标识只要求唯一、不可猜，
     * 由创建处决定生成时机，测试也因此能造出确定的标识。
     */
    public static Post create(String publicId,
                              Long authorId,
                              String title,
                              String content,
                              String coverImage,
                              List<String> tags,
                              PostStatus status,
                              Instant now) {
        Post post = new Post();
        post.publicId = publicId;
        post.authorId = authorId;
        post.title = title;
        post.content = content;
        post.coverImage = coverImage;
        post.tags = List.copyOf(tags);
        post.applyStatus(status, now);
        return post;
    }

    /**
     * 编辑：把新内容整体覆盖上去。
     *
     * 状态联动只有一处：从未发布变为已发布时写 {@code published_at}，
     * 从已发布退回草稿时置空，保持已发布则不动——否则每次改错别字都会把帖子顶到 feed 顶部。
     */
    public void edit(String title,
                     String content,
                     String coverImage,
                     List<String> tags,
                     PostStatus status,
                     Instant now) {
        this.title = title;
        this.content = content;
        this.coverImage = coverImage;
        this.tags = List.copyOf(tags);
        applyStatus(status, now);
    }

    /** 软删除：只写时间，行仍在（spec「删除是软删除」）。 */
    public void delete(Instant now) {
        markDeleted(now);
    }

    private void applyStatus(PostStatus next, Instant now) {
        if (next == PostStatus.PUBLISHED && this.publishedAt == null) {
            this.publishedAt = now;
        } else if (next == PostStatus.DRAFT) {
            this.publishedAt = null;
        }
        this.status = next;
    }

    public Long authorId() {
        return authorId;
    }

    public String title() {
        return title;
    }

    public String content() {
        return content;
    }

    public String coverImage() {
        return coverImage;
    }

    public List<String> tags() {
        return tags == null ? List.of() : tags;
    }

    public PostStatus status() {
        return status;
    }

    public Instant publishedAt() {
        return publishedAt;
    }
}
