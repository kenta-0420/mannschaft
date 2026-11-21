package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.Visibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * グローバル検索（F04.6 ARTICLE 種別）のブログ記事キーワード検索クエリ（CMP-261116-0722 PR-5）。
 *
 * <p><b>可視性述語</b>（件数と取得で同一の WHERE 句を使う）。次の<b>すべて</b>を満たす記事だけを返す:</p>
 * <ol>
 *   <li>{@code deleted_at IS NULL} かつ {@code status = PUBLISHED} かつ {@code published_at <= 現在}。
 *       {@code published_at} は UTC 格納基準（{@code hibernate.jdbc.time_zone: UTC}）なので、現在は SQL の
 *       {@code UTC_TIMESTAMP()} と比べる（セッション TZ 依存の {@code NOW()} は使わず、Java の壁時計も束縛しない。
 *       時刻方針 {@code datetime_policy_utc_instant_vs_wallclock.md} と番人 {@code RawSqlTimeColumnGuardTest}）。</li>
 *   <li>チーム記事か組織記事のどちらか一方だけ（{@code team_id} と {@code organization_id} の片方だけが非 NULL）。
 *       個人ブログ（{@code user_id} / {@code social_profile_id}）は対象外。</li>
 *   <li>{@link Condition#excludedIds()}（閲覧者が支払い条件を満たさない課金ゲート付き記事）に含まれない。</li>
 *   <li>次のいずれか（1 つの WHERE の OR なので、両経路に該当しても 1 件として数える）:
 *     <ul>
 *       <li><b>所属経路</b>: {@link ScopeGroup} ごとに「スコープ ID の IN かつ visibility の IN」。グループは
 *           呼び出し側がスコープ単位のロールから組んだもので、スコープ数ではなくロール段の数だけ並ぶ。</li>
 *       <li><b>公開経路</b>: {@code visibility = PUBLIC} かつ {@code public_visible = TRUE} かつ
 *           スコープ ID が公開スコープ（team / organization の公開 Service が判定済み）の IN。</li>
 *     </ul></li>
 * </ol>
 *
 * <p><b>時刻列</b>: 期間指定は閲覧者 TZ の壁時計とオフセットを渡し、SQL の {@code CONVERT_TZ} で UTC 格納基準へ
 * 変換してから {@code created_at} と比較する。値はすべてバインドする。</p>
 */
@Repository
public class BlogPostKeywordSearchRepository {

    private static final String MATCH = "MATCH(bp.title, bp.body) AGAINST(:keyword IN BOOLEAN MODE)";

    @PersistenceContext
    private EntityManager em;

    /**
     * 経路に依らない共通条件。
     *
     * @param booleanQuery FULLTEXT BOOLEAN MODE の検索式（空にしないこと）
     * @param authorId     作成者（null なら絞らない）
     * @param from         期間開始（null なら絞らない）
     * @param toExclusive  期間終了の翌日開始（null なら絞らない）
     */
    public record BaseCondition(String booleanQuery, Long authorId, DateBound from, DateBound toExclusive) {
    }

    /**
     * 所属経路の 1 グループ（同じ可視レベル集合を持つ同種スコープの束）。
     *
     * @param team         true ならチーム（{@code team_id}）、false なら組織（{@code organization_id}）
     * @param scopeIds     スコープ ID（空にしないこと）
     * @param visibilities そのスコープで閲覧できる visibility（空にしないこと）
     */
    public record ScopeGroup(boolean team, List<Long> scopeIds, List<String> visibilities) {
    }

    /**
     * 検索条件。
     *
     * @param base          共通条件
     * @param groups        所属経路のグループ（空可）
     * @param publicTeamIds 公開経路のチーム ID（空可）
     * @param publicOrgIds  公開経路の組織 ID（空可）
     * @param excludedIds   除外する記事 ID（閲覧者が支払い条件を満たさない課金ゲート付き。空可）
     */
    public record Condition(BaseCondition base, List<ScopeGroup> groups, List<Long> publicTeamIds,
                            List<Long> publicOrgIds, List<Long> excludedIds) {

        /** どの経路にも該当し得ないか（SQL を発行しない判定に使う）。 */
        public boolean isEmpty() {
            return groups.isEmpty() && publicTeamIds.isEmpty() && publicOrgIds.isEmpty();
        }
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
     * 公開経路の候補スコープ（共通条件と {@code visibility = PUBLIC AND public_visible = TRUE} を満たす記事の
     * 親スコープ）。
     *
     * @param teamIds チーム ID
     * @param orgIds  組織 ID
     */
    public record CandidateScopes(Set<Long> teamIds, Set<Long> orgIds) {
    }

    /**
     * 1 件分の取得結果（すべて可視性を判定した同じ SQL の行から取った値）。
     *
     * @param id             記事 ID
     * @param title          題名
     * @param body           本文（HTML）
     * @param authorId       作成者（null 可）
     * @param teamId         チーム記事のチーム ID（組織記事は null）
     * @param organizationId 組織記事の組織 ID（チーム記事は null）
     * @param slug           slug
     * @param createdAtUtc   作成日時の DB 格納値（UTC 壁時計、{@code yyyy-MM-dd HH:mm:ss.SSSSSS}。null 可）
     * @param relevance      FULLTEXT の関連度
     */
    public record ScoredPost(Long id, String title, String body, Long authorId, Long teamId, Long organizationId,
                             String slug, String createdAtUtc, double relevance) {
    }

    /**
     * 公開経路の候補スコープを列挙する（親スコープの公開判定は呼び出し側が team / organization の公開 Service で行う）。
     * キーワードに一致する公開記事を持つスコープだけを返すため、件数は全公開スコープ数ではなく一致したスコープ数に比例する。
     */
    public CandidateScopes findPublicCandidateScopes(BaseCondition base) {
        Map<String, Object> params = new HashMap<>();
        String where = baseWhere(base, params)
                + " AND bp.visibility = :publicVisibility AND bp.public_visible = TRUE";
        params.put("publicVisibility", Visibility.PUBLIC.name());
        Query query = em.createNativeQuery(
                "SELECT DISTINCT bp.team_id, bp.organization_id FROM blog_posts bp" + where);
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Set<Long> teamIds = new LinkedHashSet<>();
        Set<Long> orgIds = new LinkedHashSet<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                teamIds.add(((Number) row[0]).longValue());
            } else if (row[1] != null) {
                orgIds.add(((Number) row[1]).longValue());
            }
        }
        return new CandidateScopes(teamIds, orgIds);
    }

    /** 可視記事の件数（{@link #find} と同一の WHERE 句）。 */
    public long count(Condition condition) {
        Map<String, Object> params = new HashMap<>();
        String where = where(condition, params);
        Query query = em.createNativeQuery("SELECT COUNT(*) FROM blog_posts bp" + where);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * 可視記事を関連度→新しい順に 1 ページ分取得する（{@link #count} と同一の WHERE 句）。
     *
     * <p>結果に載せる列は、可視性を判定した<b>同じ SQL の SELECT 列</b>から取る。ID だけを取って別クエリで行を
     * 読み直すと、その間にスコープ移動等と本文の更新が同時にコミットされた場合に、判定時とは別の状態の本文を返して
     * しまう（CMP-261116-0722 PR-6 検分 P1 の横展開）。作成日時は DB の UTC 格納値を文字列で返す。</p>
     */
    public List<ScoredPost> find(Condition condition, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = where(condition, params);
        Query query = em.createNativeQuery(
                "SELECT bp.id, bp.title, bp.body, bp.author_id, bp.team_id, bp.organization_id, bp.slug,"
                + " DATE_FORMAT(bp.created_at, '%Y-%m-%d %H:%i:%s.%f') AS created_at_utc, " + MATCH + " AS relevance"
                + " FROM blog_posts bp" + where
                + " ORDER BY relevance DESC, bp.created_at DESC, bp.id DESC LIMIT :limit OFFSET :offset");
        params.forEach(query::setParameter);
        query.setParameter("limit", limit);
        query.setParameter("offset", offset);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<ScoredPost> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new ScoredPost(toLong(row[0]), text(row[1]), text(row[2]), toLong(row[3]), toLong(row[4]),
                    toLong(row[5]), text(row[6]), text(row[7]), relevance(row[8])));
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

    private static String where(Condition c, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(baseWhere(c.base(), params));
        if (!c.excludedIds().isEmpty()) {
            sb.append(" AND bp.id NOT IN (:excludedIds)");
            params.put("excludedIds", c.excludedIds());
        }
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < c.groups().size(); i++) {
            ScopeGroup g = c.groups().get(i);
            String column = g.team() ? "bp.team_id" : "bp.organization_id";
            paths.add("(" + column + " IN (:scopeIds" + i + ") AND bp.visibility IN (:visibilities" + i + "))");
            params.put("scopeIds" + i, g.scopeIds());
            params.put("visibilities" + i, g.visibilities());
        }
        List<String> publicScopes = new ArrayList<>();
        if (!c.publicTeamIds().isEmpty()) {
            publicScopes.add("bp.team_id IN (:publicTeamIds)");
            params.put("publicTeamIds", c.publicTeamIds());
        }
        if (!c.publicOrgIds().isEmpty()) {
            publicScopes.add("bp.organization_id IN (:publicOrgIds)");
            params.put("publicOrgIds", c.publicOrgIds());
        }
        if (!publicScopes.isEmpty()) {
            paths.add("(bp.visibility = :publicVisibility AND bp.public_visible = TRUE AND ("
                    + String.join(" OR ", publicScopes) + "))");
            params.put("publicVisibility", Visibility.PUBLIC.name());
        }
        if (paths.isEmpty()) {
            // 呼び出し側は空条件で SQL を発行しない約束だが、万一来ても何も返さない（安全側）
            sb.append(" AND 1 = 0");
        } else {
            sb.append(" AND (").append(String.join(" OR ", paths)).append(")");
        }
        return sb.toString();
    }

    private static String baseWhere(BaseCondition c, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE ").append(MATCH)
                .append(" AND bp.deleted_at IS NULL AND bp.status = :published")
                .append(" AND bp.published_at IS NOT NULL AND bp.published_at <= UTC_TIMESTAMP()")
                // チーム記事か組織記事のどちらか一方だけ（個人ブログ・スコープが曖昧な行は対象外）
                .append(" AND bp.user_id IS NULL AND bp.social_profile_id IS NULL")
                .append(" AND ((bp.team_id IS NOT NULL AND bp.organization_id IS NULL)")
                .append(" OR (bp.team_id IS NULL AND bp.organization_id IS NOT NULL))");
        params.put("keyword", c.booleanQuery());
        params.put("published", PostStatus.PUBLISHED.name());
        if (c.authorId() != null) {
            sb.append(" AND bp.author_id = :authorId");
            params.put("authorId", c.authorId());
        }
        if (c.from() != null) {
            sb.append(" AND bp.created_at >= CONVERT_TZ(CAST(:fromLocal AS DATETIME), :fromOffset, '+00:00')");
            params.put("fromLocal", c.from().localDateTime());
            params.put("fromOffset", c.from().utcOffset());
        }
        if (c.toExclusive() != null) {
            sb.append(" AND bp.created_at < CONVERT_TZ(CAST(:toLocal AS DATETIME), :toOffset, '+00:00')");
            params.put("toLocal", c.toExclusive().localDateTime());
            params.put("toOffset", c.toExclusive().utcOffset());
        }
        return sb.toString();
    }
}
