package com.luyi.myfirst.service.attraction.support;

import com.luyi.myfirst.common.enums.AttractionCollection;
import com.luyi.myfirst.common.enums.AttractionSortBy;
import com.luyi.myfirst.common.exception.AttractionErrorEnum;
import com.luyi.myfirst.common.exception.BizException;

import java.util.Set;

/**
 * 列表查询的参数归一化（spec「列表的城市过滤、排序与条数上限」）。
 *
 * 三条规则的强度刻意不同，因为它们的失败语义不同：
 *
 * - **city**：缺失、为空、`all`、不在白名单——一律回落成「不过滤」。
 *   景点页本身就把非法城市按 `all` 呈现，后端若改判 400，同一个 URL 会在
 *   页面层通过、在接口层失败，等于把归一化规则撕成两处。
 * - **sortBy**：缺失或非法——一律回落成 `hot`。排序是「怎么排」而不是「取哪一批」，
 *   回落的后果只是顺序不同，不会返回用户没要的数据；为此新增第三个错误码
 *   会让 D5 只有两个码的结论作废。
 * - **collection**：缺失或空白 = 完整列表；**非空但不在白名单 = 400**。
 *   榜单没有「合理的默认值可回退」——静默回落到某个榜单等于替用户改了请求，
 *   而静默回落到完整列表又会让「我要榜单却拿到全部」变成一个看不见的 bug。
 *
 * 白名单与库侧 `ck_attractions_city_slug`、前端城市表三处必须一致；
 * 这里不做第四份「城市中文名」，只认 slug。
 */
public record AttractionQuery(String citySlug,
                              AttractionSortBy sortBy,
                              AttractionCollection collection,
                              int limit) {

    /** spec：默认 20。 */
    public static final int DEFAULT_LIMIT = 20;

    /** spec：上限 50，超出钳制而不报错。 */
    public static final int MAX_LIMIT = 50;

    /** `all` 是查询侧语义（不过滤），不是数据取值，库里不会出现。 */
    private static final String ALL_CITIES = "all";

    /** 与 `ck_attractions_city_slug` 逐字一致；多一个少一个都会在写库时被 CHECK 拒绝。 */
    private static final Set<String> CITY_SLUGS = Set.of(
            "beijing", "shanghai", "guangzhou", "chengdu", "xian", "hangzhou", "sanya");

    /** 对外入口：把请求上的原始字符串一次性归一成服务层可以直接用的形状。 */
    public static AttractionQuery parse(String city, String sortBy, String collection, Integer limit) {
        return new AttractionQuery(
                normalizeCity(city),
                resolveSortBy(sortBy),
                resolveCollection(collection),
                normalizeLimit(limit));
    }

    /** 返回 {@code null} 表示不过滤城市；查询层因此不需要知道 `all` 的存在。 */
    public static String normalizeCity(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (ALL_CITIES.equals(trimmed) || !CITY_SLUGS.contains(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static AttractionSortBy resolveSortBy(String raw) {
        return AttractionSortBy.fromDbValue(raw).orElseGet(AttractionSortBy::defaultSort);
    }

    private static AttractionCollection resolveCollection(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return AttractionCollection.fromDbValue(raw.trim())
                .orElseThrow(() -> new BizException(AttractionErrorEnum.ATTRACTION_COLLECTION_INVALID));
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
