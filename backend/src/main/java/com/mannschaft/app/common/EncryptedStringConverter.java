package com.mannschaft.app.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA AttributeConverter: 文字列フィールドを透過的にAES-256-GCM暗号化/復号する。
 * <p>
 * エンティティフィールドに {@code @Convert(converter = EncryptedStringConverter.class)} を付与して使用する。
 * Spring管理外のため、{@link EncryptionServiceHolder} 経由でEncryptionServiceを取得する。
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        return EncryptionServiceHolder.getEncryptionService().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        // 暗号化導入（V9.053）前に平文のまま保存されたレガシー値（システムユーザー等のシードデータ）は
        // そのまま返す。形が暗号文なのに復号失敗する真の異常は decryptLegacyAware が例外送出する。
        return EncryptionServiceHolder.getEncryptionService().decryptLegacyAware(dbData);
    }
}
