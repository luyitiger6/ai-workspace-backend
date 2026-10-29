package com.luyi.myfirst.dao.post.converter;

import com.luyi.myfirst.common.enums.PostStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 枚举名（PUBLISHED）与库内取值（published）不是同一个东西：schema.sql 的
 * {@code ck_posts_status} 只认小写。直接用 {@code EnumType.STRING} 会写入 PUBLISHED
 * 并被 CHECK 拒绝，所以这一层不能省——与 {@code AccountStatusConverter} 是同一个理由。
 */
@Converter(autoApply = true)
public class PostStatusConverter implements AttributeConverter<PostStatus, String> {

    @Override
    public String convertToDatabaseColumn(PostStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public PostStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        for (PostStatus status : PostStatus.values()) {
            if (status.dbValue().equals(dbData)) {
                return status;
            }
        }
        throw new IllegalArgumentException("库内出现未定义的帖子状态: " + dbData);
    }
}
