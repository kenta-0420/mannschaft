package com.mannschaft.app.weather.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * GeoNames 郵便番号→緯度経度マスタエンティティ（F02.10 天気ウィジェット）。
 *
 * <p>主キー方針: マスタ例外として自然複合キー {@code (countryCode, postalCode)} を採用
 * （CLAUDE.md 原則 6 のマスタ例外条項）。全テナント共通の参照データのため
 * UUIDv7 化のメリットがなく、引き当ては必ず {@code (countryCode, postalCode)} で行う。</p>
 *
 * <p>運用は GeoNames データの月次バッチによる upsert
 * （{@code INSERT ... ON DUPLICATE KEY UPDATE}）。</p>
 */
@Entity
@Table(name = "postal_codes")
@IdClass(PostalCodeId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class PostalCodeEntity {

    /** ISO 3166-1 alpha-2 国コード。 */
    @Id
    @Column(name = "country_code", length = 2, nullable = false)
    private String countryCode;

    /** 郵便番号（JP は半角ハイフン除去後の 7 桁、国別フォーマット）。 */
    @Id
    @Column(name = "postal_code", length = 20, nullable = false)
    private String postalCode;

    /** 地名（表示用、GeoNames の生値）。 */
    @Setter
    @Column(name = "place_name", length = 180, nullable = false)
    private String placeName;

    /** 第 1 行政区画（都道府県・州）。 */
    @Setter
    @Column(name = "admin1_name", length = 100)
    private String admin1Name;

    /** 第 2 行政区画（市区町村）。 */
    @Setter
    @Column(name = "admin2_name", length = 100)
    private String admin2Name;

    /** 緯度（生値、丸め前）。 */
    @Setter
    @Column(name = "latitude", nullable = false, precision = 8, scale = 5)
    private BigDecimal latitude;

    /** 経度（生値、丸め前）。 */
    @Setter
    @Column(name = "longitude", nullable = false, precision = 8, scale = 5)
    private BigDecimal longitude;

    /** GeoNames の精度コード（1-6）。 */
    @Setter
    @Column(name = "accuracy")
    private Short accuracy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
