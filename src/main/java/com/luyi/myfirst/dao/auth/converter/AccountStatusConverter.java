package com.luyi.myfirst.dao.auth.converter;

import com.luyi.myfirst.common.enums.AccountStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 枚举名（ACTIVE）与库内取值（active）不是同一个东西：schema.sql 的 CHECK 只认小写。
 * 直接用 EnumType.STRING 会写入 ACTIVE 并被 CHECK 拒绝，所以这一层不能省。
 */
@Converter(autoApply = true)
public class AccountStatusConverter implements AttributeConverter<AccountStatus, String> {

    @Override
    public String convertToDatabaseColumn(AccountStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public AccountStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        for (AccountStatus status : AccountStatus.values()) {
            if (status.dbValue().equals(dbData)) {
                return status;
            }
        }
        throw new IllegalArgumentException("库内出现未定义的账户状态: " + dbData);
    }
}
