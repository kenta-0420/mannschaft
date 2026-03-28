package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.DatePreset;
import com.mannschaft.app.analytics.SegmentType;
import com.mannschaft.app.analytics.dto.SegmentAnalysisResponse;
import com.mannschaft.app.analytics.repository.AnalyticsDailyRevenueRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyUsersRepository;
import com.mannschaft.app.analytics.service.DateRangeResolver.DateRange;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * セグメント別分析を実行するサービス。
 *
 * <p>組織種別・チームテンプレート・組織規模・地域をセグメント軸として
 * 収益・ユーザー数を集計する。他機能テーブルへの参照はネイティブクエリで行い、
 * 他パッケージの Repository への直接依存を回避する。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class SegmentCalculationService {

    private final AnalyticsDailyRevenueRepository revenueRepository;
    private final AnalyticsDailyUsersRepository usersRepository;
    private final DateRangeResolver dateRangeResolver;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * セグメント別分析を実行する。
     *
     * @param from      開始日
     * @param to        終了日
     * @param preset    日付プリセット（from/to より優先）
     * @param segmentBy セグメント軸
     * @return セグメント分析レスポンス
     */
    public SegmentAnalysisResponse analyze(LocalDate from, LocalDate to,
                                           DatePreset preset, SegmentType segmentBy) {
        DateRange range = dateRangeResolver.resolve(from, to, preset);

        List<SegmentAnalysisResponse.SegmentItem> items = switch (segmentBy) {
            case ORG_TYPE -> analyzeByOrgType(range);
            case TEAM_TEMPLATE -> analyzeByTeamTemplate(range);
            case ORG_SIZE -> analyzeByOrgSize(range);
            case REGION -> analyzeByRegion(range);
        };

        // 合計を算出してシェア率を計算 — but SegmentItem is immutable, so we rebuild with proper values
        BigDecimal totalRevenue = items.stream()
                .map(SegmentAnalysisResponse.SegmentItem::getRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // SegmentItem fields: segment, orgCount, teamCount, userCount, revenue, arpu, churnRate
        List<SegmentAnalysisResponse.SegmentItem> enriched = items.stream()
                .map(item -> {
                    BigDecimal arpu = item.getUserCount() == 0 ? BigDecimal.ZERO
                            : item.getRevenue().divide(BigDecimal.valueOf(item.getUserCount()), 4, RoundingMode.HALF_UP);
                    return new SegmentAnalysisResponse.SegmentItem(
                            item.getSegment(), item.getOrgCount(), item.getTeamCount(),
                            item.getUserCount(), item.getRevenue(), arpu, null
                    );
                })
                .toList();

        log.debug("セグメント分析完了: segmentBy={}, items={}", segmentBy, enriched.size());

        // SegmentAnalysisResponse fields: segmentBy, segments
        return new SegmentAnalysisResponse(segmentBy.name(), enriched);
    }

    /**
     * 組織種別（org_type）別に集計する。
     */
    private List<SegmentAnalysisResponse.SegmentItem> analyzeByOrgType(DateRange range) {
        String sql = """
                SELECT o.org_type AS segment,
                       COALESCE(SUM(r.net_revenue), 0) AS revenue,
                       COUNT(DISTINCT o.id) AS user_count
                FROM organizations o
                LEFT JOIN analytics_daily_revenue r
                    ON r.organization_id = o.id
                    AND r.date BETWEEN :fromDate AND :toDate
                GROUP BY o.org_type
                ORDER BY revenue DESC
                """;

        return executeSegmentQuery(sql, range);
    }

    /**
     * チームテンプレート別に集計する。
     */
    private List<SegmentAnalysisResponse.SegmentItem> analyzeByTeamTemplate(DateRange range) {
        String sql = """
                SELECT t.template AS segment,
                       COALESCE(SUM(r.net_revenue), 0) AS revenue,
                       COUNT(DISTINCT t.id) AS user_count
                FROM teams t
                LEFT JOIN analytics_daily_revenue r
                    ON r.team_id = t.id
                    AND r.date BETWEEN :fromDate AND :toDate
                GROUP BY t.template
                ORDER BY revenue DESC
                """;

        return executeSegmentQuery(sql, range);
    }

    /**
     * 組織規模（メンバー数ベース）別に集計する。
     *
     * <p>SMALL: 1-10名, MEDIUM: 11-50名, LARGE: 51-200名, ENTERPRISE: 201名以上</p>
     */
    private List<SegmentAnalysisResponse.SegmentItem> analyzeByOrgSize(DateRange range) {
        String sql = """
                SELECT
                    CASE
                        WHEN member_count <= 10 THEN 'SMALL'
                        WHEN member_count <= 50 THEN 'MEDIUM'
                        WHEN member_count <= 200 THEN 'LARGE'
                        ELSE 'ENTERPRISE'
                    END AS segment,
                    COALESCE(SUM(rev), 0) AS revenue,
                    COUNT(*) AS user_count
                FROM (
                    SELECT o.id,
                           COUNT(DISTINCT um.user_id) AS member_count,
                           COALESCE(SUM(r.net_revenue), 0) AS rev
                    FROM organizations o
                    LEFT JOIN user_memberships um ON um.organization_id = o.id
                    LEFT JOIN analytics_daily_revenue r
                        ON r.organization_id = o.id
                        AND r.date BETWEEN :fromDate AND :toDate
                    GROUP BY o.id
                ) org_stats
                GROUP BY segment
                ORDER BY revenue DESC
                """;

        return executeSegmentQuery(sql, range);
    }

    /**
     * 地域（都道府県）別に集計する。
     */
    private List<SegmentAnalysisResponse.SegmentItem> analyzeByRegion(DateRange range) {
        String sql = """
                SELECT COALESCE(o.prefecture, 'UNKNOWN') AS segment,
                       COALESCE(SUM(r.net_revenue), 0) AS revenue,
                       COUNT(DISTINCT o.id) AS user_count
                FROM organizations o
                LEFT JOIN analytics_daily_revenue r
                    ON r.organization_id = o.id
                    AND r.date BETWEEN :fromDate AND :toDate
                GROUP BY o.prefecture
                ORDER BY revenue DESC
                """;

        return executeSegmentQuery(sql, range);
    }

    /**
     * ネイティブクエリを実行し、結果を SegmentItem リストに変換する。
     */
    @SuppressWarnings("unchecked")
    private List<SegmentAnalysisResponse.SegmentItem> executeSegmentQuery(String sql,
                                                                          DateRange range) {
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("fromDate", range.getFrom());
        query.setParameter("toDate", range.getTo());

        List<Object[]> results = query.getResultList();
        List<SegmentAnalysisResponse.SegmentItem> items = new ArrayList<>();

        for (Object[] row : results) {
            String segment = row[0] != null ? row[0].toString() : "UNKNOWN";
            BigDecimal revenue = row[1] instanceof BigDecimal bd
                    ? bd : new BigDecimal(row[1].toString());
            int userCount = row[2] instanceof Number num
                    ? num.intValue() : Integer.parseInt(row[2].toString());

            // SegmentItem fields: segment, orgCount, teamCount, userCount, revenue, arpu, churnRate
            items.add(new SegmentAnalysisResponse.SegmentItem(
                    segment, 0, 0, userCount, revenue, BigDecimal.ZERO, null
            ));
        }

        return items;
    }
}
