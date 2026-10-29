package com.luyi.myfirst.web.post.dto;

import java.util.List;

/**
 * 编辑请求体。
 *
 * 字段与 {@link PostCreateRequest} 完全相同：帖子编辑允许整体覆盖全部内容。
 * 这里同样没有作者字段，作者身份由令牌决定。
 */
public record PostUpdateRequest(
        String title,
        String content,
        String coverImage,
        List<String> tags,
        String status) {
}
