package com.mannschaft.app.advertising.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 広告日次統計エンティティ。
 *
 * <p>本番 Flyway V10.055 の {@code uk_campaign_ad_date (campaign_id, ad_id, date)} を
 * {@code uniqueConstraints} で宣言する。IT は ddl-auto=create（Hibernate が Entity から表生成・
 * Flyway 非経由）のため、これが無いと IT 表に一意制約が作られず
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}（F09.19.3 日次集計 UPSERT）が衝突せず重複行を作る。</p>
 */
@Entity
@Table(
        name = "ad_daily_stats",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_campaign_ad_date",
                columnNames = {"campaign_id", "ad_id", "date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class AdDailyStatsEntity extends BaseEntity {

    @Column(nullable = false)
    private Long campaignId;

    @Column(nullable = false)
    private Long adId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    @Builder.Default
    private long impressions = 0;

    @Column(nullable = false)
    @Builder.Default
    private long clicks = 0;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal cost = BigDecimal.ZERO;
}
