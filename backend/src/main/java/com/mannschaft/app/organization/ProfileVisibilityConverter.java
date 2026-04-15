package com.mannschaft.app.organization;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * JPA AttributeConverter: ProfileVisibility ↔ JSON 文字列。
 * DB の JSON カラムとエンティティの ProfileVisibility フィールドを相互変換する。
 * 未知のキーは FAIL_ON_UNKNOWN_PROPERTIES で検出するが、DB 保存済みデータの後方互換を守るため
 * Converter 自体では FAIL_ON_UNKNOWN_PROPERTIES = false にしておき、
 * Service 層のバリデーションで未知キーを弾く設計とする。
 */
@Slf4j
@Converter
public class ProfileVisibilityConverter implements AttributeConverter<ProfileVisibility, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String convertToDatabaseColumn(ProfileVisibility attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            log.error("ProfileVisibility のシリアライズに失敗しました", e);
            throw new IllegalStateException("ProfileVisibility のシリアライズに失敗しました", e);
        }
    }

    @Override
    public ProfileVisibility convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, ProfileVisibility.class);
        } catch (Exception e) {
            log.error("ProfileVisibility のデシリアライズに失敗しました: {}", dbData, e);
            throw new IllegalStateException("ProfileVisibility のデシリアライズに失敗しました", e);
        }
    }
}
