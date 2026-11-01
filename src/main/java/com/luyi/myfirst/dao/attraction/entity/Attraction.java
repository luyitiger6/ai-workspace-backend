package com.luyi.myfirst.dao.attraction.entity;

import com.luyi.myfirst.common.enums.AttractionCollection;
import com.luyi.myfirst.common.enums.AttractionStatus;
import com.luyi.myfirst.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 景点主体（design D2：单表 + 三个策展排序列）。
 *
 * 公共八列继承自 {@link BaseEntity}；这里只有业务列与三条具名写路径
 * （{@link #describe} / {@link #curate} / {@link #delete}）。没有 setter：
 * 榜单排名一旦允许调用方直接改某个 {@code curatedRank*} 列，
 * 「哪一列属于哪个榜单」的知识就会从枚举里漏到调用方。
 *
 * 本期没有管理后台，生产数据由 {@code schema.sql} 的种子写入；这些写路径
 * 服务于测试夹具与将来的录入能力，不是死代码。
 *
 * {@code city_slug} 只存白名单里的 7 个城市，不存 {@code all}：
 * {@code all} 是查询侧语义（不过滤），落库会把「不过滤」变成一种数据取值。
 *
 * {@code verified_at} 是可见性条件而不只是展示字段：spec 要求核实时间缺失的景点
 * 不出现在列表、榜单与详情里，因此每条读查询都带 {@code verifiedAt is not null}。
 */
@Entity
@Table(name = "attractions")
public class Attraction extends BaseEntity {

    @Column(name = "city_slug", nullable = false, length = 32)
    private String citySlug;

    @Column(name = "name_en", nullable = false, length = 120)
    private String nameEn;

    @Column(name = "name_cn", nullable = false, length = 120)
    private String nameCn;

    @Column(name = "cover_url", length = 512)
    private String coverUrl;

    @Column(nullable = false, length = 16)
    private AttractionStatus status;

    /** 发布时刻，不是创建时刻；草稿为 NULL。 */
    @Column(name = "published_at")
    private Instant publishedAt;

    /** DECIMAL(2,1)，NULL = 尚无评分（不用 0 代替「未知」，见 db-design-spec §5.1）。 */
    @Column(precision = 2, scale = 1)
    private BigDecimal rating;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    @Column(name = "free_entry", nullable = false)
    private boolean freeEntry;

    @Column(name = "booking_required", nullable = false)
    private boolean bookingRequired;

    @Column(name = "open_on_monday", nullable = false)
    private boolean openOnMonday;

    @Column(name = "summary_en", length = 300)
    private String summaryEn;

    /** 正文：不是元数据，索引策略按 §8.1 不对大文本建 B-Tree。 */
    @Column(name = "content_en", columnDefinition = "MEDIUMTEXT")
    private String contentEn;

    @Column(name = "ticket_info_en", length = 512)
    private String ticketInfoEn;

    @Column(name = "opening_hours_en", length = 512)
    private String openingHoursEn;

    @Column(name = "booking_info_en", length = 512)
    private String bookingInfoEn;

    @Column(name = "payment_info_en", length = 512)
    private String paymentInfoEn;

    @Column(name = "address_en", length = 512)
    private String addressEn;

    /** 中文地址：给出租车司机看，只允许出现在详情响应里。 */
    @Column(name = "address_cn", length = 512)
    private String addressCn;

    @Column(name = "transit_en", length = 512)
    private String transitEn;

    @Column(name = "suggested_duration_en", length = 128)
    private String suggestedDurationEn;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** NULL = 未收录进该榜单；非 NULL = 该榜内的策展顺序（从 1 起）。 */
    @Column(name = "curated_rank_first_trip")
    private Integer curatedRankFirstTrip;

    @Column(name = "curated_rank_no_booking")
    private Integer curatedRankNoBooking;

    @Column(name = "curated_rank_open_monday")
    private Integer curatedRankOpenMonday;

    protected Attraction() {
        // JPA 需要
    }

    /**
     * 建立一条景点行。
     *
     * {@code publicId} 由调用方生成并传入，与 {@code Post.create} 同一理由：
     * 对外标识只要求唯一、不可猜，由创建处决定生成时机，测试也因此能造出确定的值
     * （并列排序的断言依赖它）。
     *
     * {@code verifiedAt} 单独成参而不是默认取当前时刻：核实时间是内容侧的
     * 人工结论，不是系统时间；传 null 得到的正是「未核实」这一被测状态。
     */
    public static Attraction create(String publicId,
                                    String citySlug,
                                    String nameEn,
                                    String nameCn,
                                    String coverUrl,
                                    AttractionStatus status,
                                    Instant publishedAt,
                                    Instant verifiedAt,
                                    BigDecimal rating,
                                    long viewCount,
                                    boolean freeEntry,
                                    boolean bookingRequired,
                                    boolean openOnMonday) {
        Attraction attraction = new Attraction();
        attraction.publicId = publicId;
        attraction.citySlug = citySlug;
        attraction.nameEn = nameEn;
        attraction.nameCn = nameCn;
        attraction.coverUrl = coverUrl;
        attraction.status = status;
        attraction.publishedAt = publishedAt;
        attraction.verifiedAt = verifiedAt;
        attraction.rating = rating;
        attraction.viewCount = viewCount;
        attraction.freeEntry = freeEntry;
        attraction.bookingRequired = bookingRequired;
        attraction.openOnMonday = openOnMonday;
        return attraction;
    }

    /**
     * 写入详情页专属文案块（正文、门票、开放时间、预约、支付、地址、交通、建议时长）。
     *
     * 单独成路径而不是塞进 {@link #create}：列表页根本不需要这十个字段，
     * 让建行与写文案各自只有一个签名，测试夹具也能按需只给列表字段。
     */
    public void describe(AttractionCopy copy) {
        this.summaryEn = copy.summaryEn();
        this.contentEn = copy.contentEn();
        this.ticketInfoEn = copy.ticketInfoEn();
        this.openingHoursEn = copy.openingHoursEn();
        this.bookingInfoEn = copy.bookingInfoEn();
        this.paymentInfoEn = copy.paymentInfoEn();
        this.addressEn = copy.addressEn();
        this.addressCn = copy.addressCn();
        this.transitEn = copy.transitEn();
        this.suggestedDurationEn = copy.suggestedDurationEn();
    }

    /**
     * 榜单收录的唯一入口：{@code rank} 为 null 表示从该榜移除。
     *
     * 用枚举而不是字符串列名来选列，是为了让「新加一个榜单」在编译期就暴露
     * 未覆盖的分支——本期没有管理后台，但这条约束不随写入方缺席而失效。
     */
    public void curate(AttractionCollection collection, Integer rank) {
        switch (collection) {
            case FIRST_TRIP -> this.curatedRankFirstTrip = rank;
            case NO_BOOKING -> this.curatedRankNoBooking = rank;
            case OPEN_MONDAY -> this.curatedRankOpenMonday = rank;
        }
    }

    /** 软删除：只写时间，行仍在（spec「已删除景点按不存在处理」）。 */
    public void delete(Instant now) {
        markDeleted(now);
    }

    public String citySlug() {
        return citySlug;
    }

    public String nameEn() {
        return nameEn;
    }

    public String nameCn() {
        return nameCn;
    }

    public String coverUrl() {
        return coverUrl;
    }

    public AttractionStatus status() {
        return status;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public BigDecimal rating() {
        return rating;
    }

    public long viewCount() {
        return viewCount;
    }

    public boolean freeEntry() {
        return freeEntry;
    }

    public boolean bookingRequired() {
        return bookingRequired;
    }

    public boolean openOnMonday() {
        return openOnMonday;
    }

    public String summaryEn() {
        return summaryEn;
    }

    public String contentEn() {
        return contentEn;
    }

    public String ticketInfoEn() {
        return ticketInfoEn;
    }

    public String openingHoursEn() {
        return openingHoursEn;
    }

    public String bookingInfoEn() {
        return bookingInfoEn;
    }

    public String paymentInfoEn() {
        return paymentInfoEn;
    }

    public String addressEn() {
        return addressEn;
    }

    public String addressCn() {
        return addressCn;
    }

    public String transitEn() {
        return transitEn;
    }

    public String suggestedDurationEn() {
        return suggestedDurationEn;
    }

    public Instant verifiedAt() {
        return verifiedAt;
    }
}