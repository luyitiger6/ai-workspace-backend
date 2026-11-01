package com.luyi.myfirst.dao.attraction.converter;

import com.luyi.myfirst.common.enums.AttractionStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 枚举名（PUBLISHED）与库内取值（published）不是同一个东西：schema.sql 的
 * {@code ck_attractions_status} 只认小写。直接用 {@code EnumType.STRING}
 * 会写入 PUBLISHED 并被 CHECK 拒绝，所以这一层不能省——与
 * {@code PostStatusConverter} 是同一个理由。
 *
 * 不能复用 {@code PostStatusConverter}：它绑定的是 {@code PostStatus} 类型，
 * 换成景点状态等于让两个模块共用一个转换器。
 */
@Converter(autoApply = true)
public class AttractionStatusConverter implements AttributeConverter<AttractionStatus, String> {

    @Override
    public String convertToDatabaseColumn(AttractionStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public AttractionStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        for (AttractionStatus status : AttractionStatus.values()) {
            if (status.dbValue().equals(dbData)) {
                return status;
            }
        }
        throw new IllegalArgumentException("库内出现未定义的景点状态: " + dbData);
    }
}
