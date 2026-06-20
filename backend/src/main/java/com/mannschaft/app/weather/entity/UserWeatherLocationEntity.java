package com.mannschaft.app.weather.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * F02.10 天気ウィジェット — ユーザー地点キャッシュエンティティ。
 *
 * <p>ユーザーの居住地点を、プライバシー保護のため <b>0.5 度丸め</b>
 * （約 55km 四方）の緯度経度として保持する。郵便番号変更時に
 * {@code postal_codes} を引いて自動更新される。</p>
 *
 * <p><b>主キー方針</b>: CLAUDE.md 原則 6（2026-05-11〜）に従い
 * {@link UuidV7Entity} を継承し、{@code id BINARY(16)} で UUIDv7 を採用。</p>
 *
 * <p><b>GDPR 連携</b>: {@code @PersonalData(category = "location_preference")} により
 * {@link com.mannschaft.app.gdpr.service.PersonalDataCollector} の網羅性チェックに組み込まれる。
 * エクスポート時は country_code / postal_code_hash を除く緯度経度・地名・derived_at のみダンプ。</p>
 *
 * <p><b>FK 方針</b>: クロスドメイン FK 禁止原則に従い、{@code user_id} への FK は張らない。
 * 参照整合性は {@code UserAnonymizedEvent} リスナー（次フェーズ実装）でアプリケーション層で保証する。</p>
 */
@PersonalData(category = "location_preference")
@Entity
@Table(name = "user_weather_locations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class UserWeatherLocationEntity extends UuidV7Entity {

    /** auth ドメインの users.id（FK は張らない・原則準拠）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 地点ラベル（将来複数地点拡張用、本機能では home 固定）。 */
    @Setter
    @Column(name = "label", length = 50, nullable = false)
    private String label;

    /** users.country_code のスナップショット。 */
    @Setter
    @Column(name = "country_code", length = 2, nullable = false)
    private String countryCode;

    /** 平文郵便番号の HMAC-SHA256（APP_HMAC_SECRET 使用）。 */
    @Setter
    @Column(name = "postal_code_hash", length = 64, nullable = false)
    private String postalCodeHash;

    /** 0.5 度丸めの緯度（キャッシュキー兼）。 */
    @Setter
    @Column(name = "latitude_rounded", nullable = false, precision = 4, scale = 1)
    private BigDecimal latitudeRounded;

    /** 0.5 度丸めの経度。 */
    @Setter
    @Column(name = "longitude_rounded", nullable = false, precision = 4, scale = 1)
    private BigDecimal longitudeRounded;

    /** UI 表示用の地名スナップショット（例: 「東京都千代田区」）。 */
    @Setter
    @Column(name = "place_name_snapshot", length = 180, nullable = false)
    private String placeNameSnapshot;

    /** 導出日時。 */
    @Setter
    @Column(name = "derived_at", nullable = false)
    private LocalDateTime derivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.derivedAt == null) {
            this.derivedAt = now;
        }
        if (this.label == null) {
            this.label = "home";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
