package com.luyi.myfirst.common.enums;

import java.util.Optional;

/**
 * 景点列表的排序维度（design D1）：只表达「对全量怎么排」。
 *
 * 取值与前端 {@code /api/attractions?sortBy=} 的字面量逐字一致，改词表要同步
 * {@code frontend/lib/contracts/attractions.ts} 与服务端白名单。
 *
 * 与 {@link AttractionCollection} 刻意分开：排序是对全量集合的一种视角，
 * 榜单是人工挑出的有限集合且顺序由人定，两者共用一个参数会让
 * 「全量排序」与「有限集合」挤进同一个分支。
 */
public enum AttractionSortBy {

    HOT("hot"),
    RATING("rating"),
    NEW("new");

    private final String dbValue;

    AttractionSortBy(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 与前端查询参数一一对应。 */
    public String dbValue() {
        return dbValue;
    }

    /** 默认排序：浏览数降序（spec「列表的城市过滤、排序与条数上限」）。 */
    public static AttractionSortBy defaultSort() {
        return HOT;
    }

    /** 按字符串值查找；未知值返回 {@link Optional#empty()}，由调用方决定回退还是拒绝。 */
    public static Optional<AttractionSortBy> fromDbValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        for (AttractionSortBy sort : values()) {
            if (sort.dbValue.equals(raw)) {
                return Optional.of(sort);
            }
        }
        return Optional.empty();
    }
}
