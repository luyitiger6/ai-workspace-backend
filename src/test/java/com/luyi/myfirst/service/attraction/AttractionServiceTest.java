package com.luyi.myfirst.service.attraction;

import com.luyi.myfirst.common.enums.AttractionCollection;
import com.luyi.myfirst.common.enums.AttractionStatus;
import com.luyi.myfirst.common.exception.AttractionErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.dao.attraction.entity.Attraction;
import com.luyi.myfirst.dao.attraction.repository.AttractionRepository;
import com.luyi.myfirst.service.attraction.model.AttractionPage;
import com.luyi.myfirst.service.attraction.model.AttractionSummary;
import com.luyi.myfirst.service.attraction.model.AttractionView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 景点读能力的服务层契约（spec「只呈现已发布景点」「列表的城市过滤、排序与条数上限」
 * 「约束型榜单为人工策展集合」「核实时间可见」）。
 *
 * 三条夹具纪律：
 *
 * 1. **不断言绝对总数**：库里恒有 `schema.sql` 的 12 条已发布种子（design Risks 第 1 条），
 *    任何「总数等于 N」的断言都会随种子变动碎掉。这里只断言「返回条目满足给定条件」
 *    与「相对大小」。
 * 2. **夹具用互不相同的固定 `public_id`**：并列排序的断言需要确定的字典序，
 *    随机 ULID 会让「谁在前」变成碰运气。
 * 3. **每个用例前后清空景点读缓存**：`@Transactional` 只回滚数据库、不回滚 Redis。
 *    本类在事务内造夹具并调 `service.list()` 时，服务层会把「含夹具的结果」写进缓存；
 *    事务回滚后缓存仍然在，后续用例就会读到别人的夹具（表现为「期望 H2 实际 H1」
 *    「期望 50 条实际 13 条」这类串数据）。
 *
 * 草稿、已删除、未核实三条不可见性都放在同一个用例里造：它们共享一个「本可排在最前」
 * 的浏览数，任何一条泄漏都会直接顶掉首条。
 */
@SpringBootTest
@Transactional
class AttractionServiceTest {

    /**
     * 景点缓存键的域前缀。`RankingCache` 刻意不提供清除方法（景点没有写入口，生产用不上），
     * 所以测试侧只能自己按前缀扫。这是域级粗前缀而非精确业务键：它不参与任何断言，
     * 清多了也不会让用例变绿，因此「用生产键构造器取键」的约定在这里无构造器可借。
     */
    private static final String CACHE_PREFIX = "wc:attraction:";

    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant VERIFIED_AT = Instant.parse("2026-08-02T00:00:00Z");
    private static final BigDecimal RATING = new BigDecimal("4.0");
    private static final String NAME_EN = "Test Spot";
    private static final String NAME_CN = "测试景点";

    /** 种子首条：Forbidden City，用于详情路径的正向断言。 */
    private static final String SEED_PUBLIC_ID = "01JQATTR000000000000000001";

    @Autowired
    private AttractionService service;

    @Autowired
    private AttractionRepository attractions;

    @Autowired
    private StringRedisTemplate redis;

    /**
     * 前后各清一遍：前置清掉上一轮（含中断退出的轮次）留下的夹具快照，后置不把本类的
     * 夹具遗留给其他测试类与真实前端。用 SCAN 而不是 KEYS：Redis 是共用实例，
     * 一条 KEYS 会阻塞其他项目的请求。
     */
    @BeforeEach
    @AfterEach
    void clearAttractionCache() {
        List<String> keys = new ArrayList<>();
        try (Cursor<byte[]> cursor = redis.executeWithStickyConnection(connection -> connection.keyCommands()
                .scan(ScanOptions.scanOptions().match(CACHE_PREFIX + '*').count(500).build()))) {
            cursor.forEachRemaining(key -> keys.add(new String(key, StandardCharsets.UTF_8)));
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ---------- 城市过滤 ----------

    @Test
    @DisplayName("按城市过滤：返回条目的城市全部等于该 slug，含新造的该城景点")
    void cityFilterReturnsOnlyThatCity() {
        Attraction beijing = publishedSpot(testPublicId("C1"), "beijing", 9_500_001L);
        attractions.saveAndFlush(beijing);

        AttractionPage page = service.list("beijing", null, null, 50);

        assertThat(page.items()).isNotEmpty();
        assertThat(page.items()).allMatch(item -> "beijing".equals(item.city()));
        assertThat(ids(page)).contains(testPublicId("C1"));
    }

    @Test
    @DisplayName("city 省略或为 all 时覆盖全部城市；白名单外的城市回落成不过滤")
    void unknownCityFallsBackToAllCities() {
        Attraction guangzhou = publishedSpot(testPublicId("C2"), "guangzhou", 9_500_002L);
        attractions.saveAndFlush(guangzhou);

        AttractionPage omitted = service.list(null, null, null, 50);
        AttractionPage all = service.list("all", null, null, 50);
        AttractionPage bogus = service.list("atlantis", null, null, 50);

        assertThat(all.items()).hasSameSizeAs(omitted.items());
        assertThat(bogus.items()).hasSameSizeAs(omitted.items());
        assertThat(omitted.items()).extracting(AttractionSummary::city)
                .contains("beijing", "shanghai");
    }

    // ---------- 三种排序 ----------

    @Test
    @DisplayName("默认排序与显式 hot 一致：浏览数降序")
    void hotIsDefaultAndDescending() {
        attractions.saveAndFlush(publishedSpot(testPublicId("H1"), "hangzhou", 9_700_001L));

        AttractionPage byDefault = service.list(null, null, null, 50);
        AttractionPage byHot = service.list(null, "hot", null, 50);

        assertThat(byDefault.items().get(0).publicId()).isEqualTo(testPublicId("H1"));
        assertThat(ids(byHot)).isEqualTo(ids(byDefault));
        assertThat(views(byDefault)).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    @DisplayName("sortBy 非法时回落成 hot，而不是报错")
    void unknownSortByFallsBackToHot() {
        attractions.saveAndFlush(publishedSpot(testPublicId("H2"), "chengdu", 9_700_002L));

        AttractionPage page = service.list(null, "most-popular-ever", null, 50);

        assertThat(page.items().get(0).publicId()).isEqualTo(testPublicId("H2"));
    }

    @Test
    @DisplayName("rating 排序：最高分在前，未评分（NULL）排在最后")
    void ratingSortPutsHighestFirstAndNullLast() {
        Attraction topRated = Attraction.create(testPublicId("R1"), "xian", "Top Rated", "满分",
                null, AttractionStatus.PUBLISHED, PUBLISHED_AT, VERIFIED_AT,
                new BigDecimal("5.0"), 9_800_000L, false, true, true);
        Attraction unrated = Attraction.create(testPublicId("R2"), "xian", "Unrated", "未评分",
                null, AttractionStatus.PUBLISHED, PUBLISHED_AT, VERIFIED_AT,
                null, 9_800_001L, false, false, true);
        attractions.saveAllAndFlush(List.of(topRated, unrated));

        List<AttractionSummary> items = service.list(null, "rating", null, 50).items();

        assertThat(items.get(0).publicId()).isEqualTo(testPublicId("R1"));
        assertThat(items.get(0).rating()).isEqualByComparingTo("5.0");
        assertThat(items.get(items.size() - 1).publicId()).isEqualTo(testPublicId("R2"));
        assertThat(items.get(items.size() - 1).rating()).isNull();
    }

    @Test
    @DisplayName("new 排序：发布时刻降序，晚发布的排在前")
    void newSortUsesPublishedAtDescending() {
        Attraction newest = Attraction.create(testPublicId("W1"), "beijing", "Newest", "最新",
                null, AttractionStatus.PUBLISHED, Instant.parse("2031-01-01T00:00:00Z"),
                VERIFIED_AT, RATING, 1L, true, false, true);
        attractions.saveAndFlush(newest);

        List<AttractionSummary> items = service.list(null, "new", null, 50).items();

        assertThat(items.get(0).publicId()).isEqualTo(testPublicId("W1"));
    }

    @Test
    @DisplayName("并列时按 publicId 升序稳定返回")
    void tiesAreBrokenByPublicIdAscending() {
        String first = testPublicId("T1");
        String second = testPublicId("T2");
        attractions.saveAllAndFlush(List.of(
                publishedSpot(first, "xian", 9_600_001L),
                publishedSpot(second, "xian", 9_600_001L)));

        List<String> ids = ids(service.list(null, "hot", null, 50));

        assertThat(ids.get(0)).isEqualTo(first);
        assertThat(ids.get(1)).isEqualTo(second);
    }

    // ---------- limit ----------

    @Test
    @DisplayName("limit 默认 20、超过上限钳制到 50；total 是过滤后的总数而非本页条数")
    void limitDefaultsToTwentyAndClampsToFifty() {
        persistBulk("sanya", 9_000_000L, 51);

        AttractionPage byDefault = service.list(null, null, null, null);
        AttractionPage oversized = service.list(null, null, null, 999);

        assertThat(byDefault.items()).hasSize(20);
        assertThat(oversized.items()).hasSize(50);
        // 51 条批量夹具全部可见，总数必然不低于它；不写死种子条数。
        assertThat(oversized.total()).isGreaterThanOrEqualTo(51L);
        assertThat(oversized.items().get(0).viewCount()).isEqualTo(9_000_050L);
    }

    @Test
    @DisplayName("limit 小于 1 时回落成默认值")
    void nonPositiveLimitFallsBackToDefault() {
        persistBulk("sanya", 8_900_000L, 25);

        assertThat(service.list(null, null, null, 0).items()).hasSize(20);
        assertThat(service.list(null, null, null, -5).items()).hasSize(20);
    }

    // ---------- 榜单 ----------

    @Test
    @DisplayName("榜单只含策展成员：满足约束但未被收录的景点不出现")
    void collectionOnlyContainsCuratedMembers() {
        // 两条都满足「无需预约」，但只有一条被策展收录。
        Attraction curated = Attraction.create(testPublicId("N2"), "chengdu", "Curated", "已收录",
                null, AttractionStatus.PUBLISHED, PUBLISHED_AT, VERIFIED_AT, RATING,
                9_900_001L, true, false, true);
        Attraction notCurated = Attraction.create(testPublicId("N1"), "chengdu", "Uncurated", "未收录",
                null, AttractionStatus.PUBLISHED, PUBLISHED_AT, VERIFIED_AT, RATING,
                9_900_002L, true, false, true);
        curated.curate(AttractionCollection.NO_BOOKING, 901);
        attractions.saveAllAndFlush(List.of(curated, notCurated));

        List<String> ids = ids(service.list(null, null, "no-booking", 50));

        assertThat(ids).contains(testPublicId("N2"));
        assertThat(ids).doesNotContain(testPublicId("N1"));
    }

    @Test
    @DisplayName("榜单顺序等于策展顺序，不按浏览数或评分重排")
    void collectionOrderFollowsCuratedRank() {
        Attraction later = publishedSpot(testPublicId("K2"), "hangzhou", 9_950_002L);
        Attraction earlier = publishedSpot(testPublicId("K1"), "hangzhou", 9_950_001L);
        later.curate(AttractionCollection.FIRST_TRIP, 903);
        earlier.curate(AttractionCollection.FIRST_TRIP, 902);
        attractions.saveAllAndFlush(List.of(later, earlier));

        List<String> ids = ids(service.list(null, null, "first-trip", 50));

        assertThat(ids).contains(testPublicId("K1"), testPublicId("K2"));
        // 名次 902 的 K1 必须先于名次 903 的 K2，且与浏览数大小无关（K2 的浏览数更高）。
        assertThat(ids.indexOf(testPublicId("K1"))).isLessThan(ids.indexOf(testPublicId("K2")));
    }

    @Test
    @DisplayName("榜单的 limit 生效，但 total 仍是成员总数")
    void collectionLimitAppliesButTotalCountsMembers() {
        AttractionPage unlimited = service.list(null, null, "first-trip", 50);
        AttractionPage limited = service.list(null, null, "first-trip", 2);

        assertThat(limited.items()).hasSize(2);
        assertThat(limited.total()).isEqualTo(unlimited.total());
        assertThat(limited.total()).isGreaterThan((long) limited.items().size());
    }

    @Test
    @DisplayName("榜单可按城市过滤")
    void collectionRespectsCityFilter() {
        Attraction beijingMember = publishedSpot(testPublicId("M1"), "beijing", 9_960_001L);
        Attraction xianMember = publishedSpot(testPublicId("M2"), "xian", 9_960_002L);
        beijingMember.curate(AttractionCollection.OPEN_MONDAY, 911);
        xianMember.curate(AttractionCollection.OPEN_MONDAY, 912);
        attractions.saveAllAndFlush(List.of(beijingMember, xianMember));

        List<AttractionSummary> items = service.list("beijing", null, "open-monday", 50).items();

        assertThat(items).isNotEmpty();
        assertThat(items).allMatch(item -> "beijing".equals(item.city()));
        assertThat(items).extracting(AttractionSummary::publicId).doesNotContain(testPublicId("M2"));
    }

    @Test
    @DisplayName("collection 取白名单外的值：400 ATTRACTION_COLLECTION_INVALID")
    void invalidCollectionIsRejected() {
        BizException error = rejectionOf(() -> service.list(null, null, "hidden-gems", 20));

        assertThat(error.getErrorCode())
                .isEqualTo(AttractionErrorEnum.ATTRACTION_COLLECTION_INVALID.getErrorCode());
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------- 可见性 ----------

    @Test
    @DisplayName("草稿、已删除、未核实的景点都不进列表 / 榜单 / 详情")
    void invisibleAttractionsNeverAppear() {
        Attraction draft = Attraction.create(testPublicId("D1"), "shanghai", "Draft", "草稿",
                null, AttractionStatus.DRAFT, null, VERIFIED_AT, RATING,
                9_999_001L, true, false, true);
        draft.curate(AttractionCollection.FIRST_TRIP, 1);

        Attraction unverified = Attraction.create(testPublicId("D2"), "shanghai", "Unverified", "未核实",
                null, AttractionStatus.PUBLISHED, PUBLISHED_AT, null, RATING,
                9_999_002L, true, false, true);
        unverified.curate(AttractionCollection.FIRST_TRIP, 2);

        Attraction deleted = publishedSpot(testPublicId("D3"), "shanghai", 9_999_003L);
        deleted.curate(AttractionCollection.FIRST_TRIP, 3);
        deleted.delete(Instant.now());

        attractions.saveAllAndFlush(List.of(draft, unverified, deleted));

        List<String> listIds = ids(service.list(null, "hot", null, 50));
        List<String> curatedIds = ids(service.list(null, null, "first-trip", 50));

        assertThat(listIds).doesNotContain(
                testPublicId("D1"), testPublicId("D2"), testPublicId("D3"));
        assertThat(curatedIds).doesNotContain(
                testPublicId("D1"), testPublicId("D2"), testPublicId("D3"));
        // 三条的浏览数都远高于种子，若泄漏必是首条。
        assertThat(listIds.get(0)).isNotIn(testPublicId("D1"), testPublicId("D2"), testPublicId("D3"));

        for (String publicId : List.of(testPublicId("D1"), testPublicId("D2"), testPublicId("D3"))) {
            assertNotFound(() -> service.read(publicId));
        }
    }

    // ---------- 详情 ----------

    @Test
    @DisplayName("详情返回列表字段之外的决策字段，核实时间非空")
    void readReturnsDetailFields() {
        AttractionView view = service.read(SEED_PUBLIC_ID);

        assertThat(view.publicId()).isEqualTo(SEED_PUBLIC_ID);
        assertThat(view.nameEn()).isEqualTo("Forbidden City");
        assertThat(view.city()).isEqualTo("beijing");
        assertThat(view.rating()).isNotNull();
        assertThat(view.verifiedAt()).isNotNull();
        assertThat(view.contentEn()).isNotBlank();
        assertThat(view.ticketInfoEn()).isNotBlank();
        assertThat(view.addressCn()).isNotBlank();
    }

    @Test
    @DisplayName("未知与空 publicId 一律 404 ATTRACTION_NOT_FOUND")
    void missingPublicIdIsNotFound() {
        assertNotFound(() -> service.read(Ulid.generate()));
        assertNotFound(() -> service.read(" "));
        assertNotFound(() -> service.read(null));
    }

    // ---------- 夹具 ----------

    /** 26 字符：10 位固定前缀 + 14 个 0 + 2 位序号，保证字典序与入参顺序一致。 */
    private static String testPublicId(String tail) {
        return "01KTIETEST" + "0".repeat(14) + tail;
    }

    private static Attraction publishedSpot(String publicId, String city, long viewCount) {
        return Attraction.create(publicId, city, NAME_EN, NAME_CN, null,
                AttractionStatus.PUBLISHED, PUBLISHED_AT, VERIFIED_AT, RATING,
                viewCount, true, false, true);
    }

    /**
     * 批量夹具：{@code limit} 的默认值与上限只有数据量超过 50 时才可观测，
     * 少了这一批，`limit` 的断言会退化成恒真。
     */
    private void persistBulk(String city, long baseViewCount, int count) {
        List<Attraction> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(Attraction.create(
                    String.format("01KTIEBULK%s%02d", "0".repeat(14), i),
                    city, "Bulk Spot " + i, "批量景点" + i, null,
                    AttractionStatus.PUBLISHED, PUBLISHED_AT, VERIFIED_AT, RATING,
                    baseViewCount + i, true, false, true));
        }
        attractions.saveAllAndFlush(rows);
    }

    private static List<String> ids(AttractionPage page) {
        return page.items().stream().map(AttractionSummary::publicId).toList();
    }

    private static List<Long> views(AttractionPage page) {
        return page.items().stream().map(AttractionSummary::viewCount).toList();
    }

    private static BizException rejectionOf(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BizException.class);
        return (BizException) thrown;
    }

    private static void assertNotFound(ThrowingCallable call) {
        BizException error = rejectionOf(call);
        assertThat(error.getErrorCode()).isEqualTo(AttractionErrorEnum.ATTRACTION_NOT_FOUND.getErrorCode());
        assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}