package com.luyi.myfirst.common.enums;

import java.util.Optional;

/**
 * 约束型榜单的固定键（spec「约束型榜单为人工策展集合」，design D2）。
 *
 * 与 {@link AttractionSortBy} 刻意分开：排序是对全量集合的一种视角，
 * 榜单是人工挑出的有限集合且顺序由人定。共用一个参数会让服务层不得不
 * 用「值是不是合法排序键」来决定走哪条分支。
 *
 * 取值为契约字面量，与前端 {@code /api/attractions?collection=} 逐字一致；
 * 白名单外取值由服务层转成 {@code ATTRACTION_COLLECTION_INVALID}（400），
 * 而不是静默回落到任何一个榜单。
 */
public enum AttractionCollection {

    FIRST_TRIP("first-trip"),
    NO_BOOKING("no-booking"),
    OPEN_MONDAY("open-monday");

    private final String dbValue;

    AttractionCollection(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 与前端查询参数一一对应。 */
    public String dbValue() {
        return dbValue;
    }

    /**
     * 按字符串值查找；未知值返回 {@link Optional#empty()}。
     *
     * 返回 Optional 而不是抛异常，是为了让「省略参数」与「非法参数」在调用方
     * 分开处理：前者走全量列表，后者必须 400。
     */
    public static Optional<AttractionCollection> fromDbValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        for (AttractionCollection collection : values()) {
            if (collection.dbValue.equals(raw)) {
                return Optional.of(collection);
            }
        }
        return Optional.empty();
    }
}
