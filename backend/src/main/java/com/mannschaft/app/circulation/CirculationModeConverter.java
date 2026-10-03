package com.mannschaft.app.circulation;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * JPA AttributeConverter: {@link CirculationMode} ↔ DB 文字列。
 *
 * <p>{@code circulation_documents.circulation_mode} には、アプリケーションが書き込んだことのない
 * 不正な文字列（例: {@code PARALLEL}）が共有開発DBに混入した事例がある（CMP-260920-1041）。
 * Hibernate 標準の {@code @Enumerated(EnumType.STRING)} は未知の文字列を読んだ時点で
 * {@link IllegalArgumentException}（{@code No enum constant ...}）を投げ、それが 1 行でもあると
 * 一覧取得クエリ全体が失敗し、他の正常な行まで巻き添えで表示できなくなる。
 *
 * <p>本 Converter は読み込み時に未知の値を握りつぶさず {@link #log} に ERROR として残した上で、
 * 縮退値 {@link CirculationMode#UNKNOWN} へ写像する。これにより異常行を含む一覧でも取得自体は
 * 成功し、異常の存在はログから追跡できる。新規作成入力の {@code UNKNOWN} は
 * {@link com.mannschaft.app.circulation.service.CirculationService#createDocument} が拒否する。
 * 既存の異常行を更新すると、妥当なモードを推測せず異常の痕跡を保持するため、DB 値は
 * 明示値 {@code UNKNOWN} へ正規化される。
 */
@Slf4j
@Converter(autoApply = false)
public class CirculationModeConverter implements AttributeConverter<CirculationMode, String> {

    @Override
    public String convertToDatabaseColumn(CirculationMode attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.name();
    }

    @Override
    public CirculationMode convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return CirculationMode.valueOf(dbData);
        } catch (IllegalArgumentException e) {
            log.error("circulation_documents.circulation_mode に未知の値が入っています。"
                    + "UNKNOWN として縮退表示します: {}", dbData, e);
            return CirculationMode.UNKNOWN;
        }
    }
}
