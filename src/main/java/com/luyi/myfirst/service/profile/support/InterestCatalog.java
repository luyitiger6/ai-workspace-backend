package com.luyi.myfirst.service.profile.support;

import java.util.List;

/**
 * 兴趣标签清单：资料更新校验与界面展示的唯一真源（spec「兴趣标签清单可匿名读取」）。
 *
 * 清单的取值直接决定 D6 的存储形式能不能成立：含半角逗号会破坏逗号分隔编码，
 * 含首尾空白会让「strip 后与原文等长」的判定失真。这些不变量不是靠注释承诺的，
 * 而是由 ProfileRulesTest 逐条钉住（非空、无重复、不含半角逗号、无首尾空白）。
 *
 * 取值是英文主题词，与既有界面文案口径对齐（design Q1 候选）。清单会演进：
 * 调整只改这里，不改 DDL 也不改校验门槛。
 */
public final class InterestCatalog {

    public static final List<String> INTERESTS = List.of(
            "hiking",
            "food",
            "photography",
            "budget travel",
            "camping",
            "cycling",
            "museums",
            "nightlife",
            "beaches",
            "mountains",
            "city walks",
            "local culture",
            "wildlife",
            "road trips",
            "volunteering",
            "luxury");

    private InterestCatalog() {
    }

    /** 取值是否在清单内。清单是唯一真源，判定只经这里，不复制一份到调用方。 */
    public static boolean contains(String interest) {
        return INTERESTS.contains(interest);
    }
}
