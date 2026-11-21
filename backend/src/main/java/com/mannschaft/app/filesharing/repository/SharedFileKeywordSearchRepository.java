package com.mannschaft.app.filesharing.repository;

import com.mannschaft.app.filesharing.FileScopeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * グローバル検索（F04.6 FILE 種別）の共有ファイルキーワード検索クエリ（CMP-261116-0722 PR-6）。
 *
 * <p><b>可視性述語</b>（件数と取得で同一の SQL を使う）。次の<b>すべて</b>を満たすファイルだけを返す:</p>
 * <ol>
 *   <li>ファイルが未削除（{@code shared_files.deleted_at IS NULL}）で、{@code name} / {@code description} が
 *       FULLTEXT（{@code ft_shared_files_name_description}）に一致する。</li>
 *   <li>直属フォルダの {@code scope_type} が TEAM / ORGANIZATION で、そのスコープが {@link ScopeGroup} のいずれかに
 *       含まれる（種別とスコープ列は必ずペアで照合する。大会フォルダは {@code organization_id} に主催組織を持つため
 *       {@code scope_type} で必ず弾く。PERSONAL / TOURNAMENT / TOURNAMENT_DIVISION は対象外）。</li>
 *   <li>ファイル自身の {@code min_visible_role} が NULL か、そのスコープのグループの可視レベルに含まれる。</li>
 *   <li>ファイル自身に {@code file_permissions} 行が無い。</li>
 *   <li>直属フォルダから根までの<b>全フォルダ</b>（再帰 CTE {@code folder_path} で一括解決）について、
 *       実在する・未削除・直属フォルダと同じスコープ（{@code scope_type} / {@code team_id} / {@code organization_id}）・
 *       {@code min_visible_role} が NULL かグループの可視レベルに含まれる・{@code file_permissions} 行が無い。
 *       祖先を {@value #MAX_FOLDER_DEPTH} 段辿っても根に届かない（循環を含む）パスは不可視とする。
 *       判定が不定（NULL）になった場合も不可視に倒す（{@code COALESCE(..., FALSE)}）。</li>
 * </ol>
 *
 * <p><b>時刻列</b>: 期間指定は閲覧者 TZ の壁時計とオフセットを渡し、SQL の {@code CONVERT_TZ} で UTC 格納基準へ
 * 変換してから {@code created_at} と比較する（Java の時刻値は束縛しない。番人 {@code RawSqlTimeColumnGuardTest}）。
 * 値はすべてバインドする。</p>
 */
@Repository
public class SharedFileKeywordSearchRepository {

    /** 祖先フォルダを辿る上限段数（これを超えるパス・循環パスは不可視）。 */
    static final int MAX_FOLDER_DEPTH = 64;

    private static final String MATCH = "MATCH(sf.name, sf.description) AGAINST(:keyword IN BOOLEAN MODE)";

    @PersistenceContext
    private EntityManager em;

    /**
     * スコープに依らない共通条件。
     *
     * @param booleanQuery FULLTEXT BOOLEAN MODE の検索式（空にしないこと）
     * @param authorId     アップロード者（{@code created_by}。null なら絞らない）
     * @param from         期間開始（null なら絞らない）
     * @param toExclusive  期間終了の翌日開始（null なら絞らない）
     */
    public record BaseCondition(String booleanQuery, Long authorId, DateBound from, DateBound toExclusive) {
    }

    /**
     * 同じ可視レベル集合を持つ同種スコープの束。
     *
     * @param team     true ならチーム（{@code scope_type = TEAM} かつ {@code team_id}）、false なら組織
     *                 （{@code scope_type = ORGANIZATION} かつ {@code organization_id}）
     * @param scopeIds スコープ ID（空にしないこと）
     * @param levels   そのスコープで満たす {@code min_visible_role} の値（空にしないこと）
     */
    public record ScopeGroup(boolean team, List<Long> scopeIds, List<String> levels) {
    }

    /**
     * 検索条件。
     *
     * @param base   共通条件
     * @param groups スコープのグループ（空なら SQL を発行しないこと）
     */
    public record Condition(BaseCondition base, List<ScopeGroup> groups) {
    }

    /**
     * 閲覧者 TZ の日付境界。
     *
     * @param localDateTime {@code yyyy-MM-dd HH:mm:ss} 形式の閲覧者 TZ の壁時計
     * @param utcOffset     その時点の UTC オフセット（{@code +09:00} 形式）
     */
    public record DateBound(String localDateTime, String utcOffset) {
    }

    /**
     * 1 件分の取得結果（すべて可視性を判定した同じ SQL の行から取った値）。
     *
     * @param id           ファイル ID
     * @param name         ファイル名
     * @param description  説明（null 可）
     * @param createdBy    アップロード者（null 可）
     * @param createdAtUtc 作成日時の DB 格納値（UTC 壁時計、{@code yyyy-MM-dd HH:mm:ss.SSSSSS}。null 可）
     * @param team         直属フォルダがチームスコープなら true（false なら組織スコープ）
     * @param scopeId      直属フォルダのスコープ ID
     * @param relevance    FULLTEXT の関連度
     */
    public record ScoredFile(Long id, String name, String description, Long createdBy, String createdAtUtc,
                             boolean team, Long scopeId, double relevance) {
    }

    /** 可視ファイルの件数（{@link #find} と同一の述語）。 */
    public long count(Condition condition) {
        Map<String, Object> params = new HashMap<>();
        String sql = withFolderPath(condition, params) + "SELECT COUNT(*) FROM shared_files sf"
                + " JOIN shared_folders fo ON fo.id = sf.folder_id" + visibleWhere(condition, params);
        Query query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * 可視ファイルを関連度→新しい順に 1 ページ分取得する（{@link #count} と同一の述語）。
     *
     * <p>結果に載せる列（名前・説明・アップロード者・作成日時・直属フォルダのスコープ）は、可視性を判定した
     * <b>同じ SQL の SELECT 列</b>から取る。ID だけを取って別クエリで行を読み直すと、その間にフォルダ移動と説明文の
     * 更新が同時にコミットされた場合に、移動前の可視性で通った行の移動後の内容を返してしまう（CMP-261116-0722 PR-6
     * 検分 P1）。作成日時は DB の UTC 格納値を文字列で返す（{@link ScoredFile#createdAtUtc}）。</p>
     */
    public List<ScoredFile> find(Condition condition, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String sql = withFolderPath(condition, params)
                + "SELECT sf.id, sf.name, sf.description, sf.created_by,"
                + " DATE_FORMAT(sf.created_at, '%Y-%m-%d %H:%i:%s.%f') AS created_at_utc,"
                + " fo.scope_type, fo.team_id, fo.organization_id, " + MATCH + " AS relevance"
                + " FROM shared_files sf JOIN shared_folders fo ON fo.id = sf.folder_id"
                + visibleWhere(condition, params)
                + " ORDER BY relevance DESC, sf.created_at DESC, sf.id DESC LIMIT :limit OFFSET :offset";
        Query query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        query.setParameter("limit", limit);
        query.setParameter("offset", offset);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<ScoredFile> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            boolean team = FileScopeType.TEAM.name().equals(String.valueOf(row[5]));
            Long scopeId = toLong(team ? row[6] : row[7]);
            if (scopeId == null) {
                continue;
            }
            result.add(new ScoredFile(toLong(row[0]), text(row[1]), text(row[2]), toLong(row[3]), text(row[4]),
                    team, scopeId, relevance(row[8])));
        }
        return result;
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static double relevance(Object value) {
        return value == null ? 0d : ((Number) value).doubleValue();
    }

    /**
     * 候補ファイル（共通条件・スコープ条件を満たすもの）ごとに、直属フォルダから根までのフォルダを列挙する
     * 再帰 CTE。{@code depth = 1} が直属フォルダ。削除済みフォルダも辿る（判定は本体側で行う）。
     */
    private static String withFolderPath(Condition c, Map<String, Object> params) {
        params.put("maxDepth", MAX_FOLDER_DEPTH);
        return "WITH RECURSIVE folder_path (file_id, folder_id, depth) AS ("
                + " SELECT sf.id, sf.folder_id, 1 FROM shared_files sf"
                + " JOIN shared_folders fo ON fo.id = sf.folder_id" + candidateWhere(c, params)
                + " UNION ALL"
                + " SELECT fp.file_id, pf.parent_id, fp.depth + 1 FROM folder_path fp"
                + " JOIN shared_folders pf ON pf.id = fp.folder_id"
                + " WHERE pf.parent_id IS NOT NULL AND fp.depth < :maxDepth"
                + ") ";
    }

    /** 本体の述語: 候補条件 ＋ ファイル自身の最低可視ロール・個別権限 ＋ パス上の全フォルダの判定。 */
    private static String visibleWhere(Condition c, Map<String, Object> params) {
        return candidateWhere(c, params)
                + " AND COALESCE(" + levelSatisfied("sf.min_visible_role", c) + ", FALSE)"
                + " AND NOT EXISTS (SELECT 1 FROM file_permissions fpm"
                + " WHERE fpm.target_type = 'FILE' AND fpm.target_id = sf.id)"
                + " AND NOT EXISTS (SELECT 1 FROM folder_path fp"
                + " LEFT JOIN shared_folders a ON a.id = fp.folder_id"
                + " WHERE fp.file_id = sf.id AND NOT COALESCE("
                + "a.id IS NOT NULL AND a.deleted_at IS NULL"
                + " AND a.scope_type = fo.scope_type"
                + " AND a.team_id <=> fo.team_id AND a.organization_id <=> fo.organization_id"
                + " AND (fp.depth < :maxDepth OR a.parent_id IS NULL)"
                + " AND " + levelSatisfied("a.min_visible_role", c)
                + " AND NOT EXISTS (SELECT 1 FROM file_permissions fpa"
                + " WHERE fpa.target_type = 'FOLDER' AND fpa.target_id = a.id)"
                + ", FALSE))";
    }

    /**
     * 最低可視ロール列 {@code column} が、直属フォルダ {@code fo} のスコープでの閲覧者のレベルで満たされるか。
     * パラメータ名は {@link #candidateWhere} が登録したものを使う。
     */
    private static String levelSatisfied(String column, Condition c) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < c.groups().size(); i++) {
            parts.add("(" + scopeMatch(c.groups().get(i), i) + " AND " + column + " IN (:levels" + i + "))");
        }
        return "(" + column + " IS NULL" + (parts.isEmpty() ? "" : " OR " + String.join(" OR ", parts)) + ")";
    }

    private static String scopeMatch(ScopeGroup g, int i) {
        return g.team()
                ? "(fo.scope_type = 'TEAM' AND fo.team_id IN (:scopeIds" + i + "))"
                : "(fo.scope_type = 'ORGANIZATION' AND fo.organization_id IN (:scopeIds" + i + "))";
    }

    /** 候補条件（一致・未削除・スコープ・アップロード者・期間）。 */
    private static String candidateWhere(Condition c, Map<String, Object> params) {
        BaseCondition b = c.base();
        StringBuilder sb = new StringBuilder(" WHERE ").append(MATCH).append(" AND sf.deleted_at IS NULL");
        params.put("keyword", b.booleanQuery());
        List<String> scopes = new ArrayList<>();
        for (int i = 0; i < c.groups().size(); i++) {
            ScopeGroup g = c.groups().get(i);
            scopes.add(scopeMatch(g, i));
            params.put("scopeIds" + i, g.scopeIds());
            params.put("levels" + i, g.levels());
        }
        if (scopes.isEmpty()) {
            // 呼び出し側は空条件で SQL を発行しない約束だが、万一来ても何も返さない（安全側）
            sb.append(" AND 1 = 0");
        } else {
            sb.append(" AND (").append(String.join(" OR ", scopes)).append(")");
        }
        if (b.authorId() != null) {
            sb.append(" AND sf.created_by = :authorId");
            params.put("authorId", b.authorId());
        }
        if (b.from() != null) {
            sb.append(" AND sf.created_at >= CONVERT_TZ(CAST(:fromLocal AS DATETIME), :fromOffset, '+00:00')");
            params.put("fromLocal", b.from().localDateTime());
            params.put("fromOffset", b.from().utcOffset());
        }
        if (b.toExclusive() != null) {
            sb.append(" AND sf.created_at < CONVERT_TZ(CAST(:toLocal AS DATETIME), :toOffset, '+00:00')");
            params.put("toLocal", b.toExclusive().localDateTime());
            params.put("toOffset", b.toExclusive().utcOffset());
        }
        return sb.toString();
    }
}
