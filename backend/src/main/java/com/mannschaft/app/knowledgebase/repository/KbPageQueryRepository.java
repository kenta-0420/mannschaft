package com.mannschaft.app.knowledgebase.repository;

import com.mannschaft.app.knowledgebase.PageAccessLevel;
import com.mannschaft.app.knowledgebase.entity.KbPageEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ナレッジベースページクエリリポジトリ。
 * JdbcTemplate を使用した複雑なクエリを担当する。
 */
@Repository
public class KbPageQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public KbPageQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 指定パスプレフィックス配下の全子孫ページを取得する。
     * ページ移動・削除時の子孫一括処理に使用する。
     *
     * @param pathPrefix 対象パスのプレフィックス（例: /1/5/）
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @return 子孫ページのリスト
     */
    public List<Long> findIdsByPathPrefixAndScope(String pathPrefix, String scopeType, Long scopeId) {
        String sql = """
                SELECT id FROM kb_pages
                WHERE scope_type = ?
                  AND scope_id = ?
                  AND path LIKE ?
                  AND deleted_at IS NULL
                ORDER BY path ASC
                """;
        return jdbcTemplate.queryForList(sql, Long.class, scopeType, scopeId, pathPrefix + "%");
    }

    /**
     * スラッグの重複チェックを行う。
     * 同一スコープ内でのスラッグ一意性を確認する（自分自身は除外）。
     *
     * @param slug      確認するスラッグ
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param excludeId 除外するページID（更新時に自分自身を除くため）
     * @return 重複する場合は true
     */
    public boolean existsBySlugAndScope(String slug, String scopeType, Long scopeId, Long excludeId) {
        String sql = """
                SELECT COUNT(*) FROM kb_pages
                WHERE scope_type = ?
                  AND scope_id = ?
                  AND slug = ?
                  AND deleted_at IS NULL
                  AND id != ?
                """;
        Long count = jdbcTemplate.queryForObject(sql, Long.class, scopeType, scopeId, slug, excludeId == null ? -1L : excludeId);
        return count != null && count > 0;
    }

    /**
     * 全文検索でページを検索する。
     * FULLTEXT INDEX (ft_kbp_search) を使用して title・body を対象に検索する。
     *
     * @param keyword     検索キーワード
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param accessLevel アクセスレベルフィルター（null の場合はフィルターなし）
     * @return 検索結果のページIDリスト（スコアの高い順）
     */
    public List<Long> searchFullText(String keyword, String scopeType, Long scopeId, PageAccessLevel accessLevel) {
        StringBuilder sql = new StringBuilder("""
                SELECT id FROM kb_pages
                WHERE scope_type = ?
                  AND scope_id = ?
                  AND deleted_at IS NULL
                  AND status = 'PUBLISHED'
                  AND MATCH(title, body) AGAINST(? IN BOOLEAN MODE)
                """);

        if (accessLevel != null) {
            sql.append("  AND access_level = '").append(accessLevel.name()).append("'\n");
        }

        sql.append("ORDER BY MATCH(title, body) AGAINST(? IN BOOLEAN MODE) DESC\nLIMIT 100");

        return jdbcTemplate.queryForList(sql.toString(), Long.class, scopeType, scopeId, keyword, keyword);
    }
}
