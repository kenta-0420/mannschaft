package com.mannschaft.app.organization;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * JPA AttributeConverter: ProfileVisibility ↔ JSON 文字列。
 * DB の JSON カラムとエンティティの ProfileVisibility フィールドを相互変換する。
 */
@Slf4j
@Converter
@RequiredArgsConstructor
public class ProfileVisibilityConverter implements AttributeConverter<ProfileVisibility, String> {

    private final ObjectMapper objectMapper;

    @Override
    public String convertToDatabaseColumn(ProfileVisibility attribute) {
        if (attribute == null) return null;
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (Exception e) {
            log.error("ProfileVisibility のシリアライズに失敗しました", e);
            throw new IllegalStateException("ProfileVisibility のシリアライズに失敗しました", e);
        }
    }

    @Override
    public ProfileVisibility convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return objectMapper.readValue(dbData, ProfileVisibility.class);
        } catch (Exception e) {
            log.error("ProfileVisibility のデシリアライズに失敗しました: {}", dbData, e);
            throw new IllegalStateException("ProfileVisibility のデシリアライズに失敗しました", e);
        }
    }
}
