package com.mannschaft.app.gamification.repository;

import com.mannschaft.app.gamification.ActionType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * ポイントトランザクション集計クエリリポジトリ。
 * JdbcTemplate を使用した集計クエリを提供する。
 */
@Repository
@RequiredArgsConstructor
public class PointTransactionQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 当日の指定アクション種別のポイント付与件数を取得する（daily_limitチェック用）。
     *
     * @param userId     ユーザーID
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param actionType アクション種別
     * @param today      対象日
     * @return 当日の付与件数
     */
    public int countTodayByUserAndActionType(Long userId, String scopeType, Long scopeId,
                                             ActionType actionType, LocalDate today) {
        String sql = """
                SELECT COUNT(*)
                FROM point_transactions
                WHERE user_id = ?
                  AND scope_type = ?
                  AND scope_id = ?
                  AND action_type = ?
                  AND earned_on = ?
                  AND transaction_type = 'EARN'
                """;
        Integer count = jdbcTemplate.queryForObject(
                sql, Integer.class,
                userId, scopeType, scopeId, actionType.name(), today);
        return count != null ? count : 0;
    }

    /**
     * 指定期間のユーザーポイント合計を取得する（ポイント残高計算用）。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param from      開始日
     * @param to        終了日
     * @return ポイント合計
     */
    public int sumPointsByUserAndPeriod(Long userId, String scopeType, Long scopeId,
                                        LocalDate from, LocalDate to) {
        String sql = """
                SELECT COALESCE(SUM(points), 0)
                FROM point_transactions
                WHERE user_id = ?
                  AND scope_type = ?
                  AND scope_id = ?
                  AND earned_on BETWEEN ? AND ?
                """;
        Integer sum = jdbcTemplate.queryForObject(
                sql, Integer.class,
                userId, scopeType, scopeId, from, to);
        return sum != null ? sum : 0;
    }

    /**
     * 当日の管理者ポイント調整件数を取得する（adminAdjustPoint の上限チェック用）。
     *
     * @param userId    対象ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param today     対象日
     * @return 当日の調整件数
     */
    public int countAdminAdjustsByUserAndDate(Long userId, String scopeType, Long scopeId, LocalDate today) {
        String sql = """
                SELECT COUNT(*)
                FROM point_transactions
                WHERE user_id = ?
                  AND scope_type = ?
                  AND scope_id = ?
                  AND action_type = 'ADMIN_ADJUST'
                  AND earned_on = ?
                """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, userId, scopeType, scopeId, today);
        return count != null ? count : 0;
    }

    /**
     * 指定期間の上位ユーザーリストを取得する（ランキング計算用）。
     * 各Mapのキー: user_id (Long), total_points (Long)
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param from      開始日
     * @param to        終了日
     * @param limit     取得件数
     * @return ユーザーIDとポイント合計のマップリスト（ポイント降順）
     */
    public List<Map<String, Object>> findTopUsersByPeriod(String scopeType, Long scopeId,
                                                          LocalDate from, LocalDate to, int limit) {
        String sql = """
                SELECT user_id, SUM(points) AS total_points
                FROM point_transactions
                WHERE scope_type = ?
                  AND scope_id = ?
                  AND earned_on BETWEEN ? AND ?
                GROUP BY user_id
                ORDER BY total_points DESC
                LIMIT ?
                """;
        return jdbcTemplate.queryForList(sql, scopeType, scopeId, from, to, limit);
    }
}
