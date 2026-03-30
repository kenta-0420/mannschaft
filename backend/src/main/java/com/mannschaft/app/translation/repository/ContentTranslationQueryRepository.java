package com.mannschaft.app.translation.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 翻訳コンテンツ・クエリリポジトリ（JdbcTemplate使用）。
 * 集計・原文更新検知など、JPA派生クエリでは表現しにくい処理を担う。
 */
@Repository
@RequiredArgsConstructor
public class ContentTranslationQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 原文が更新されたと判断される翻訳コンテンツのIDリストを返す。
     * source_updated_at が現在の原文 updated_at と一致しないレコードを対象とする。
     *
     * @param sourceType              原文種別
     * @param sourceId                原文ID
     * @param currentSourceUpdatedAt  現在の原文 updated_at（文字列形式 "yyyy-MM-dd HH:mm:ss"）
     * @return 対象翻訳コンテンツIDのリスト
     */
    public List<Long> findNeedsUpdateBySourceTypeAndSourceId(
            String sourceType, Long sourceId, String currentSourceUpdatedAt) {
        String sql = """
                SELECT id
                FROM content_translations
                WHERE source_type = ?
                  AND source_id = ?
                  AND source_updated_at != ?
                  AND deleted_at IS NULL
                """;
        return jdbcTemplate.queryForList(sql, Long.class, sourceType, sourceId, currentSourceUpdatedAt);
    }

    /**
     * スコープ内の翻訳コンテンツをステータス別に集計する（ダッシュボード用）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return ステータス文字列をキー、件数を値とするMap
     */
    public Map<String, Long> countByStatusGrouped(String scopeType, Long scopeId) {
        String sql = """
                SELECT status, COUNT(*) AS cnt
                FROM content_translations
                WHERE scope_type = ?
                  AND scope_id = ?
                  AND deleted_at IS NULL
                GROUP BY status
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, scopeType, scopeId);
        Map<String, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String status = (String) row.get("status");
            Long count = ((Number) row.get("cnt")).longValue();
            result.put(status, count);
        }
        return result;
    }
}
