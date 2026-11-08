package com.luyi.myfirst.service.profile.model;

import java.util.List;

/**
 * 本人完整资料的服务层视图：昵称、头像、简介与兴趣标签四项。
 *
 * interests 是去重后的标签列表，与存储侧的逗号分隔字符串是两种形态：
 * 服务层对外一律用列表，序列化/反序列化只发生在 service 与 dao 的边界。
 * 不含 id / email / status / deletedAt 等内部字段。
 */
public record ProfileView(
        String publicId,
        String displayName,
        String avatarUrl,
        String bio,
        List<String> interests) {
}
