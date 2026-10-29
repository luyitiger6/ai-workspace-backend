package com.luyi.myfirst.service.post.model;

import com.luyi.myfirst.common.enums.PostStatus;

import java.time.Instant;
import java.util.List;

/**
 * 列表项的视图：详情白名单的子集（不含 {@code content}），并叠加三个互动统计。
 *
 * 列表不带正文有两个理由：一是 feed 里没人读全文，二是 {@code content} 是 MEDIUMTEXT，
 * 一页 20 条就能拉出上百 KiB 的无用负载。
 *
 * 三个统计字段是契约变更（spec R4）：它们只出现在列表项，不出现在详情 {@link PostView}。
 */
public record PostSummary(String publicId,
                          String title,
                          String coverImage,
                          List<String> tags,
                          PostStatus status,
                          String authorPublicId,
                          Instant createdAt,
                          Instant updatedAt,
                          long commentCount,
                          long upVoteCount,
                          long bookmarkCount) {
}
