package com.mannschaft.app.analytics.repository;

import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.entity.PageViewDailyStatsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ページビュー日次集計リポジトリ（F10.8 アクセス解析）。
 *
 * <p>日次バッチが「1 スコープ 1 日 1 行」で upsert する（{@code uk_pvds_scope_date} で保証）。
 * バッチの冪等性のため、集計対象日の既存行を削除してから再挿入できる
 * {@link #deleteByScopeTypeAndScopeIdAndDate} を提供する。GET の期間集約は
 * {@code (scope_type, scope_id)} でスコープ絞りしたうえで日付範囲を読む（{@code daily}）。
 * {@code monthly} / {@code summary} はアプリ層で集約する（設計書 §4.3）。</p>
 */
public interface PageViewDailyStatsRepository extends JpaRepository<PageViewDailyStatsEntity, UUID> {

    /**
     * バッチ冪等用: 指定スコープ・指定日の既存集計行を削除する
     * （再集計時に {@code delete → insert} で 1 行を保つ）。
     */
    void deleteByScopeTypeAndScopeIdAndDate(
            PageViewScopeType scopeType, Long scopeId, LocalDate date);

    /**
     * 指定スコープ・指定日の集計行を取得する（存在チェック / upsert 判定用）。
     */
    PageViewDailyStatsEntity findByScopeTypeAndScopeIdAndDate(
            PageViewScopeType scopeType, Long scopeId, LocalDate date);

    /**
     * 期間集約（daily）: 指定スコープの {@code [dateFrom, dateTo]} を日付昇順で取得する。
     * {@code uk_pvds_scope_date} のプレフィクス {@code (scope_type, scope_id)} を活かす。
     */
    List<PageViewDailyStatsEntity> findByScopeTypeAndScopeIdAndDateBetweenOrderByDateAsc(
            PageViewScopeType scopeType, Long scopeId, LocalDate dateFrom, LocalDate dateTo);

    /**
     * 期間集約（summary の近似フォールバック用）: 指定スコープ・期間の日次値を合算する
     * （{@code uniqueVisitors} は日次合算だと重複ありの上振れ近似になる点に注意・設計書 §4.3。
     * 保持期間内の正確値は {@link PageViewLogRepository#countUniqueVisitors} を使う）。
     */
    @Query("""
            SELECT COALESCE(SUM(s.totalViews), 0)     AS totalViews,
                   COALESCE(SUM(s.uniqueVisitors), 0) AS uniqueVisitors,
                   COALESCE(SUM(s.memberViews), 0)    AS memberViews,
                   COALESCE(SUM(s.guestViews), 0)     AS guestViews
            FROM PageViewDailyStatsEntity s
            WHERE s.scopeType = :scopeType
              AND s.scopeId   = :scopeId
              AND s.date BETWEEN :dateFrom AND :dateTo
            """)
    PeriodSummary sumForPeriod(
            @Param("scopeType") PageViewScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo);

    /**
     * 期間集約（monthly）: 指定スコープ・期間の日次値を {@code YEAR(date), MONTH(date)} で
     * 月集約する（別テーブルを作らず本テーブルをアプリ層集約・設計書 §4.3）。
     * {@code uniqueVisitors} は日次合算のため月内重複ありの近似値。
     */
    @Query("""
            SELECT YEAR(s.date)  AS year,
                   MONTH(s.date) AS month,
                   COALESCE(SUM(s.totalViews), 0)     AS totalViews,
                   COALESCE(SUM(s.uniqueVisitors), 0) AS uniqueVisitors,
                   COALESCE(SUM(s.memberViews), 0)    AS memberViews,
                   COALESCE(SUM(s.guestViews), 0)     AS guestViews
            FROM PageViewDailyStatsEntity s
            WHERE s.scopeType = :scopeType
              AND s.scopeId   = :scopeId
              AND s.date BETWEEN :dateFrom AND :dateTo
            GROUP BY YEAR(s.date), MONTH(s.date)
            ORDER BY YEAR(s.date) ASC, MONTH(s.date) ASC
            """)
    List<MonthlyAggregate> aggregateMonthlyForPeriod(
            @Param("scopeType") PageViewScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo);

    /**
     * 期間サマリの投影（日次値の合算）。
     */
    interface PeriodSummary {
        long getTotalViews();

        long getUniqueVisitors();

        long getMemberViews();

        long getGuestViews();
    }

    /**
     * 月次集約の投影（{@code YEAR(date), MONTH(date)} 単位）。
     */
    interface MonthlyAggregate {
        int getYear();

        int getMonth();

        long getTotalViews();

        long getUniqueVisitors();

        long getMemberViews();

        long getGuestViews();
    }
}
