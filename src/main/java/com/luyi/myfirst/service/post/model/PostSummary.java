package com.luyi.myfirst.service.post.model;

import com.luyi.myfirst.common.enums.PostStatus;

import java.time.Instant;
import java.util.List;

/**
 * 列表项的视图：白名单的子集，少一个 {@code content}。
 *
 * 列表不带正文有两个理由：一是 feed 里没人读全文，二是 {@code content} 是 MEDIUMTEXT，
 * 一页 20 条就能拉出上百 KiB 的无用负载。白名单的判据是「列表项 ∪ 详情 = 九个字段」，
 * 少字段不破坏它，多字段才破坏——所以这里绝不能顺手加 {@code contentPreview} 之类的派生字段。
 */
public record PostSummary(String publicId,
                          String title,
                          String coverImage,
                          List<String> tags,
                          PostStatus status,
                          String authorPublicId,
                          Instant createdAt,
                          Instant updatedAt) {
}
