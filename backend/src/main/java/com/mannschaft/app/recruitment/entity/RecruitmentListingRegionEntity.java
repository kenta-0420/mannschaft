package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F22.1 市 Phase2 D: 複数地域募集（N:N）の地域中間表エンティティ。
 *
 * <p>1 つの札（{@code listing_id}）に対し複数の地域（都道府県 / 市区町村）を紐づける。
 * 「東京＋神奈川」や「同一県内の複数市」のような複数地域募集を表現する
 * （01_data_model / 02_api_design §4）。</p>
 *
 * <h2>地域の粒度</h2>
 * <ul>
 *   <li>市区町村単位 → {@code prefectureCode} ＋ {@code cityCode}（cityCode の上位2桁＝prefectureCode）</li>
 *   <li>県単位        → {@code prefectureCode} のみ・{@code cityCode} は NULL</li>
 * </ul>
 * {@code prefectureCode} は県単位でも必須（DB NOT NULL）。
 *
 * <h2>FK 方針</h2>
 * <p>{@code listing_id} は同一ドメイン（recruitment）につき FK + ON DELETE CASCADE。
 * {@code prefecture_code}（prefectures）/ {@code city_code}（cities）はクロスドメインのため
 * FK なし・index のみ（CLAUDE.md 原則 1・2）。整合性は {@code MarketRegionValidator} で検証する。</p>
 *
 * <h2>主キー</h2>
 * <p>新規テーブルにつき {@link UuidV7Entity} を継承（CLAUDE.md 原則 6）。
 * バックフィル行のみ UUID v4 を許容（V71.008・中間表ゆえ整列不要）。</p>
 */
@Entity
@Table(name = "recruitment_listing_regions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class RecruitmentListingRegionEntity extends UuidV7Entity {

    /** 札ID（recruitment_listings.id・同一ドメイン FK CASCADE）。 */
    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    /** 都道府県コード（JIS X 0401・CHAR(2)）。県単位でも必須。 */
    @Column(name = "prefecture_code", nullable = false, length = 2)
    private String prefectureCode;

    /** 市区町村コード（JIS X 0402・CHAR(5)）。県単位は NULL。 */
    @Column(name = "city_code", length = 5)
    private String cityCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /**
     * 地域中間行を生成する。
     *
     * @param listingId      札ID（必須）
     * @param prefectureCode 都道府県コード（必須・正規化済み）
     * @param cityCode       市区町村コード（県単位は null）
     * @return 地域中間行
     */
    public static RecruitmentListingRegionEntity of(Long listingId, String prefectureCode, String cityCode) {
        if (listingId == null) {
            throw new IllegalArgumentException("listing_id は必須です");
        }
        if (prefectureCode == null || prefectureCode.isBlank()) {
            throw new IllegalArgumentException("prefecture_code は必須です");
        }
        return RecruitmentListingRegionEntity.builder()
                .listingId(listingId)
                .prefectureCode(prefectureCode)
                .cityCode(cityCode)
                .build();
    }
}
