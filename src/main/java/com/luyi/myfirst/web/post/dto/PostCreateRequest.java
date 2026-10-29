package com.luyi.myfirst.web.post.dto;

import java.util.List;

/**
 * 发布请求体。
 *
 * **没有作者字段**：作者取自令牌（design D6）。请求体里即使硬塞一个
 * {@code authorPublicId}，Jackson 的默认行为是忽略未知字段，值不会被采信——
 * 这一点由 web 层用例实际发一次带作者字段的请求来钉死，而不是靠注释承诺。
 *
 * {@code status} 缺省为 {@code draft}；取值只认 {@code draft} / {@code published}，
 * 其它值一律 {@code 400 VALIDATION_FAILED}。
 */
public record PostCreateRequest(
        String title,
        String content,
        String coverImage,
        List<String> tags,
        String status) {
}
