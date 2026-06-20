package com.mannschaft.app.weather.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * GeoNames データのバージョン管理用シングルトンエンティティ。
 *
 * <p>主キー方針: シングルトン例外（CLAUDE.md 原則 6 のシングルトン例外条項）。
 * {@code CHECK (id = 1)} で行数 1 を強制するため UUIDv7 化は無意味。</p>
 */
@Entity
@Table(name = "geonames_metadata")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class GeonamesMetadataEntity {

    /** 固定値 1（シングルトン制約）。 */
    @Id
    @Column(name = "id", columnDefinition = "TINYINT UNSIGNED")
    private Short id;

    /** 最終取り込み日時。 */
    @Setter
    @Column(name = "last_imported_at", nullable = false)
    private LocalDateTime lastImportedAt;

    /** GeoNames のダウンロードファイル更新日（例: allCountries-20260501）。 */
    @Setter
    @Column(name = "source_version", length = 50, nullable = false)
    private String sourceVersion;

    /** 取り込み行数。 */
    @Setter
    @Column(name = "imported_row_count", nullable = false)
    private Long importedRowCount;

    /** 手動実行ユーザー（cron 自動実行は NULL）。 */
    @Setter
    @Column(name = "imported_by_user_id")
    private Long importedByUserId;
}
