package com.mannschaft.app.analytics.entity;

import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * ページビュー日次集計エンティティ（F10.8 アクセス解析）。
 *
 * <p>日次バッチが前日分を集計して「1 スコープ 1 日 1 行」で upsert する。
 * 手本は {@code analytics_daily_users}（V10.069 の {@code UNIQUE KEY(date)}）を
 * 「スコープ×日付」に拡張したもの。{@code BaseEntity}（{@code BIGINT}/IDENTITY）を継承する。</p>
 *
 * <p>{@code UNIQUE KEY uk_pvds_scope_date (scope_type, scope_id, date)} により
 * 冪等な upsert を保証する。クロスドメイン FK は張らない（CLAUDE.md 原則 1）。</p>
 */
@Entity
@Table(name = "page_view_daily_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class PageViewDailyStatsEntity extends BaseEntity {

    /** スコープ種別（{@code TEAM} / {@code ORGANIZATION}）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PageViewScopeType scopeType;

    /** チーム/組織 ID（FK なし・アプリ層で整合性保証）。 */
    @Column(nullable = false)
    private Long scopeId;

    /** 集計対象日。 */
    @Column(nullable = false)
    private LocalDate date;

    /** 当日総 PV。 */
    @Builder.Default
    @Column(nullable = false)
    private int totalViews = 0;

    /** 当日ユニーク訪問者（COALESCE(user_id, visitor_id) の DISTINCT）。 */
    @Builder.Default
    @Column(nullable = false)
    private int uniqueVisitors = 0;

    /** 当日メンバー PV（user_id IS NOT NULL）。 */
    @Builder.Default
    @Column(nullable = false)
    private int memberViews = 0;

    /** 当日ゲスト PV（user_id IS NULL）。 */
    @Builder.Default
    @Column(nullable = false)
    private int guestViews = 0;
}
