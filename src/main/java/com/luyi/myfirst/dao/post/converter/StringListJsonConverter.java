package com.luyi.myfirst.dao.post.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 标签在库内是 JSON 列（design D5：JSON 只放元数据），在实体里是 {@code List<String>}。
 *
 * 转换放在持久化层而不是服务层：让「JSON 列」这件事止步于 {@code dao}，
 * 服务与 Web 层看到的始终是字符串列表，不必人人都记得序列化一次。
 *
 * 刻意不标 {@code autoApply}：{@code List<String>} 这个类型不专属于标签，
 * 自动应用会让日后任何字符串列表字段被悄悄当成 JSON 存。
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (IOException e) {
            throw new UncheckedIOException("标签序列化失败", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(MAPPER.readValue(dbData, TYPE));
        } catch (IOException e) {
            throw new UncheckedIOException("标签反序列化失败: " + dbData, e);
        }
    }
}
