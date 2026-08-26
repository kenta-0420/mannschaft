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
     * 第2弾 人気コンテンツランキング: 指定スコープ・{@code [from, to)} の生ログを
     * {@code (content_type, content_id)} 別に集計し、views 降順（同数は uniqueVisitors 降順）で
     * 上位 {@code limit} 件を返す（設計書 §5.3 第2弾）。
     *
     * <p>{@code idx_pvl_scope_content (scope_type, scope_id, content_id)} を活かすため常にスコープで絞る。
     * title / url は「同一コンテンツ内で最新 {@code viewed_at} の値」を代表値とする。MySQL の
     * {@code ONLY_FULL_GROUP_BY} 下で集計関数外カラムを安全に取り出すため、
     * {@code SUBSTRING_INDEX(GROUP_CONCAT(col ORDER BY viewed_at DESC SEPARATOR 0x1e), 0x1e, 1)}
     * イディオムで最新行の値を得る（区切りは制御文字 RS=0x1e。title は 255 文字・url は 512 文字上限で
     * 先頭要素は必ず {@code group_concat_max_len} 既定 1024 バイト内に収まるため切り詰められない）。
     * uniqueVisitors は型混在を避けるプレフィクス付き連結キー
     * {@code COUNT(DISTINCT COALESCE(CONCAT('u:', user_id), CONCAT('v:', visitor_id)))} で数える
     * （設計書 §3.4）。</p>
     *
     * @param scopeType スコープ種別（enum 名の文字列。{@code TEAM} / {@code ORGANIZATION}）
     * @param scopeId   チーム/組織 ID
     * @param from      集計開始日時（含む）
     * @param to        集計終了日時（含まない）
     * @param limit     取得上限件数
     * @return コンテンツ別ランキング行（views 降順）
     */
    @Query(value = """
            SELECT
                content_type AS contentType,
                content_id   AS contentId,
                SUBSTRING_INDEX(GROUP_CONCAT(title ORDER BY viewed_at DESC SEPARATOR 0x1e), 0x1e, 1) AS title,
                SUBSTRING_INDEX(GROUP_CONCAT(url   ORDER BY viewed_at DESC SEPARATOR 0x1e), 0x1e, 1) AS url,
                COUNT(*) AS views,
                COUNT(DISTINCT COALESCE(CONCAT('u:', user_id), CONCAT('v:', visitor_id))) AS uniqueVisitors
            FROM page_view_logs
            WHERE scope_type = :scopeType
              AND scope_id   = :scopeId
              AND viewed_at >= :from AND viewed_at < :to
            GROUP BY content_type, content_id
            ORDER BY views DESC, uniqueVisitors DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ContentRankingProjection> findTopContent(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("limit") int limit);

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

    /**
     * 第2弾 人気コンテンツランキングの集計行の投影。
     * {@link #findTopContent(String, Long, LocalDateTime, LocalDateTime, int)} の戻り型。
     */
    interface ContentRankingProjection {
        /** 閲覧対象種別（enum 名の文字列。{@code ARTICLE} / {@code ACTIVITY} / {@code PAGE} / {@code TEAM}）。 */
        String getContentType();

        /** 閲覧対象 ID（ID を持たない種別は 0）。 */
        long getContentId();

        /** 代表タイトル（同一コンテンツ内で最新 {@code viewed_at} の値）。 */
        String getTitle();

        /** 代表 URL（同一コンテンツ内で最新 {@code viewed_at} の値・アプリ内相対パス）。 */
        String getUrl();

        /** 期間内の総 PV。 */
        long getViews();

        /** 期間内のユニーク訪問者数。 */
        long getUniqueVisitors();
    }
}
