package com.mannschaft.app.incident.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * インシデントクエリリポジトリ（JdbcTemplate使用）。
 * バッチ処理用の特殊クエリを提供する。
 */
@Repository
@RequiredArgsConstructor
public class IncidentQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * SLA超過対象のインシデントIDを返す。
     * sla_deadline < now かつ is_sla_breached = 0 かつ status が未完了状態のものを対象とする。
     *
     * @param now 現在日時
     * @return SLA超過対象のインシデントIDリスト
     */
    public List<Long> findBreachedIncidents(LocalDateTime now) {
        String sql = """
                SELECT id FROM incidents
                WHERE sla_deadline < ?
                  AND is_sla_breached = 0
                  AND status IN ('REPORTED', 'ACKNOWLEDGED', 'IN_PROGRESS')
                  AND deleted_at IS NULL
                """;
        return jdbcTemplate.queryForList(sql, Long.class, now);
    }

    /**
     * 自動クローズ対象のインシデントIDを返す。
     * status が CONFIRMED かつ updated_at < threshold のものを対象とする。
     *
     * @param threshold 閾値日時（この日時より前に更新されたものが対象）
     * @return 自動クローズ対象のインシデントIDリスト
     */
    public List<Long> findConfirmedOlderThan(LocalDateTime threshold) {
        String sql = """
                SELECT id FROM incidents
                WHERE status = 'CONFIRMED'
                  AND updated_at < ?
                  AND deleted_at IS NULL
                """;
        return jdbcTemplate.queryForList(sql, Long.class, threshold);
    }
}
