package com.mannschaft.app.analytics.repository;

import com.mannschaft.app.analytics.entity.PageViewLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * ページビュー生ログリポジトリ（F10.8 アクセス解析）。
 *
 * <p>集計はネイティブクエリで行う。ユニーク訪問者は型混在（{@code user_id}=BIGINT /
 * {@code visitor_id}=CHAR(36)）を避けるため、プレフィクス付き文字列連結
 * {@code COUNT(DISTINCT COALESCE(CONCAT('u:', user_id), CONCAT('v:', visitor_id)))} で数える
 * （設計書 §3.4 / §4.3）。WHERE は {@code idx_pvl_scope_viewed (scope_type, scope_id, viewed_at)}
 * を活かすため常にスコープ×期間で絞る。</p>
 */
public interface PageViewLogRepository extends JpaRepository<PageViewLogEntity, UUID> {

    /**
     * 日次集計バッチ用: 指定期間の生ログを scope 単位で集計する
     * （通常は 1 日分＝ {@code [dayStart, dayEnd)} を渡し、scope 別の 1 日 1 行を得る）。
     *
     * <p>{@code viewed_at} は {@code [from, to)} の半開区間で絞る（{@code from} 以上 {@code to} 未満）。
     * {@code idx_pvl_scope_viewed} を活かすため {@code GROUP BY scope_type, scope_id}。</p>
     *
     * @param from 集計開始日時（含む）
     * @param to   集計終了日時（含まない）
     * @return scope 別の集計行（total/unique/member/guest）
     */
    @Query(value = """
            SELECT
                scope_type AS scopeType,
                scope_id   AS scopeId,
                COUNT(*)   AS totalViews,
                COUNT(DISTINCT COALESCE(CONCAT('u:', user_id), CONCAT('v:', visitor_id))) AS uniqueVisitors,
                SUM(CASE WHEN user_id IS NOT NULL THEN 1 ELSE 0 END) AS memberViews,
                SUM(CASE WHEN user_id IS NULL     THEN 1 ELSE 0 END) AS guestViews
            FROM page_view_logs
            WHERE viewed_at >= :from AND viewed_at < :to
            GROUP BY scope_type, scope_id
            """, nativeQuery = true)
    List<ScopeDailyAggregate> aggregateByScopeForPeriod(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 期間サマリの総 PV 件数（指定スコープ・{@code [from, to)}）。
     * {@code idx_pvl_scope_viewed} を活かす。
     */
    @Query(value = """
            SELECT COUNT(*)
            FROM page_view_logs
            WHERE scope_type = :scopeType
              AND scope_id   = :scopeId
              AND viewed_at >= :from AND viewed_at < :to
            """, nativeQuery = true)
    long countTotalViews(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 期間サマリのメンバー PV 件数（{@code user_id IS NOT NULL}・指定スコープ・{@code [from, to)}）。
     */
    @Query(value = """
            SELECT COUNT(*)
            FROM page_view_logs
            WHERE scope_type = :scopeType
              AND scope_id   = :scopeId
              AND user_id IS NOT NULL
              AND viewed_at >= :from AND viewed_at < :to
            """, nativeQuery = true)
    long countMemberViews(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 期間サマリのゲスト PV 件数（{@code user_id IS NULL}・指定スコープ・{@code [from, to)}）。
     */
    @Query(value = """
            SELECT COUNT(*)
            FROM page_view_logs
            WHERE scope_type = :scopeType
              AND scope_id   = :scopeId
              AND user_id IS NULL
              AND viewed_at >= :from AND viewed_at < :to
            """, nativeQuery = true)
    long countGuestViews(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 期間サマリのユニーク訪問者数（生ログから直接 DISTINCT・保持期間内で正確値）。
     *
     * <p>型混在を避けるためプレフィクス付き連結キー
     * {@code COUNT(DISTINCT COALESCE(CONCAT('u:', user_id), CONCAT('v:', visitor_id)))} を使う
     * （設計書 §3.4）。{@code idx_pvl_scope_viewed} / {@code idx_pvl_scope_visitor} を活かすため
     * スコープ×期間で絞る（フルスキャン回避）。</p>
     */
    @Query(value = """
            SELECT COUNT(DISTINCT COALESCE(CONCAT('u:', user_id), CONCAT('v:', visitor_id)))
            FROM page_view_logs
            WHERE scope_type = :scopeType
              AND scope_id   = :scopeId
              AND viewed_at >= :from AND viewed_at < :to
            """, nativeQuery = true)
    long countUniqueVisitors(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 日次集計バッチ用の scope 別集計行の投影。
     * {@link #aggregateByScopeForPeriod(LocalDateTime, LocalDateTime)} の戻り型。
     */
    interface ScopeDailyAggregate {
        /** スコープ種別（enum 名の文字列。{@code TEAM} / {@code ORGANIZATION}）。 */
        String getScopeType();

        /** チーム/組織 ID。 */
        Long getScopeId();

        /** 当日総 PV。 */
        long getTotalViews();

        /** 当日ユニーク訪問者。 */
        long getUniqueVisitors();

        /** 当日メンバー PV。 */
        long getMemberViews();

        /** 当日ゲスト PV。 */
        long getGuestViews();
    }
}
