package com.luyi.myfirst.dao.post.converter;

import com.luyi.myfirst.common.enums.VoteDirection;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 投票方向枚举名（UP）与库内取值（up）不是同一个东西：schema.sql 的
 * {@code ck_post_votes_direction} 只认小写。
 */
@Converter(autoApply = true)
public class VoteDirectionConverter implements AttributeConverter<VoteDirection, String> {

    @Override
    public String convertToDatabaseColumn(VoteDirection attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public VoteDirection convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        for (VoteDirection direction : VoteDirection.values()) {
            if (direction.dbValue().equals(dbData)) {
                return direction;
            }
        }
        throw new IllegalArgumentException("库内出现未定义的投票方向: " + dbData);
    }
}
