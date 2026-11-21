package com.mannschaft.app.timeline.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * グローバル検索（F04.6 POST 種別）のタイムライン投稿キーワード検索クエリ（CMP-261116-0722 PR-2）。
 *
 * <p><b>可視性述語</b>（件数と取得で同一の WHERE 句を使う）は {@link TimelinePostRepository#SEARCH_QUERY}
 * （既存 {@code GET /timeline/search}）の scope 述語と同じ:</p>
 * <ul>
 *   <li>{@code deleted_at IS NULL AND status = 'PUBLISHED'}（投稿者本人の下書き・予約・非表示も出さない）</li>
 *   <li>{@code scope_type = 'PUBLIC'}</li>
 *   <li>{@code scope_type = 'TEAM' AND scope_id IN 閲覧者の所属チーム}</li>
 *   <li>{@code scope_type = 'ORGANIZATION' AND scope_id IN 閲覧者の所属組織}（距離 0。配信指定に関係なく届く）</li>
 *   <li>配下配信: 距離 1 の祖先組織の {@code CHILDREN / DESCENDANTS}、距離 2 以上の祖先組織の {@code DESCENDANTS}</li>
 *   <li>{@code scope_type = 'PERSONAL' AND user_id = 閲覧者}</li>
 * </ul>
 * <p>VILLAGE・FRIEND_* はどの OR 句にも該当しないため出ない。スコープ指定時は所属 TEAM / ORGANIZATION の
 * 句だけを使う（PUBLIC・PERSONAL・配下配信は含めない）。空の ID 集合は呼び出し側がダミー値で埋めて渡す。</p>
 *
 * <p><b>時刻列</b>: 期間指定は Java の壁時計を時刻列へ束縛しない（datetime_policy §4.1）。閲覧者 TZ の日付と
 * UTC オフセットを渡し、SQL の {@code CONVERT_TZ} で UTC 格納基準へ変換してから比較する。</p>
 *
 * <p>値はすべてバインドし、所属スコープ数は IN 句のパラメータ 1 個に収まる（所属数に比例した SQL は発行しない）。</p>
 */
@Repository
public class TimelinePostKeywordSearchRepository {

    private static final String MATCH = "MATCH(p.content) AGAINST(:keyword IN BOOLEAN MODE)";

    @PersistenceContext
    private EntityManager em;

    /**
     * 検索条件（可視性の材料は呼び出し側で解決済み。IN 句用の集合は空にしないこと）。
     *
     * @param booleanQuery    FULLTEXT BOOLEAN MODE の検索式（空にしないこと）
     * @param viewerUserId    閲覧者（PERSONAL の一致判定）
     * @param teamIds         直接所属チーム ID（空ならダミー値）
     * @param orgIds          直接所属組織 ID（空ならダミー値）
     * @param nearOrgIds      距離 1 の配信元組織 ID（空ならダミー値）
     * @param farOrgIds       距離 2 以上の配信元組織 ID（空ならダミー値）
     * @param scopeRestricted スコープ指定あり（true なら所属 TEAM / ORGANIZATION の句だけを使う）
     * @param authorId        投稿者（null なら絞らない。個人として投稿したものだけが対象）
     * @param from            期間開始（null なら絞らない）
     * @param toExclusive     期間終了の翌日開始（null なら絞らない）
     */
    public record Condition(String booleanQuery, Long viewerUserId, List<Long> teamIds, List<Long> orgIds,
                            List<Long> nearOrgIds, List<Long> farOrgIds, boolean scopeRestricted, Long authorId,
                            DateBound from, DateBound toExclusive) {
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
     * @param id           投稿 ID
     * @param content      本文（null 可）
     * @param userId       投稿者の利用者 ID（null 可）
     * @param postedAsType 投稿名義（USER / TEAM / ORGANIZATION 等）
     * @param scopeType    スコープ種別名（TEAM / ORGANIZATION / PUBLIC / PERSONAL）
     * @param scopeId      スコープ ID（PERSONAL は利用者 ID）
     * @param createdAtUtc 作成日時の DB 格納値（UTC 壁時計、{@code yyyy-MM-dd HH:mm:ss.SSSSSS}。null 可）
     * @param relevance    FULLTEXT の関連度
     */
    public record ScoredPost(Long id, String content, Long userId, String postedAsType, String scopeType,
                             Long scopeId, String createdAtUtc, double relevance) {
    }

    /** 可視投稿の件数（{@link #find} と同一の WHERE 句）。 */
    public long count(Condition condition) {
        Map<String, Object> params = new HashMap<>();
        String where = where(condition, params);
        Query query = em.createNativeQuery("SELECT COUNT(*) FROM timeline_posts p" + where);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * 可視投稿を関連度→新しい順に 1 ページ分取得する（{@link #count} と同一の WHERE 句）。
     *
     * <p>結果に載せる列は、可視性を判定した<b>同じ SQL の SELECT 列</b>から取る。ID だけを取って別クエリで行を
     * 読み直すと、その間にスコープ移動等と本文の更新が同時にコミットされた場合に、判定時とは別の状態の本文を返して
     * しまう（CMP-261116-0722 PR-6 検分 P1 の横展開）。作成日時は DB の UTC 格納値を文字列で返す。</p>
     */
    public List<ScoredPost> find(Condition condition, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = where(condition, params);
        Query query = em.createNativeQuery(
                "SELECT p.id, p.content, p.user_id, p.posted_as_type, p.scope_type, p.scope_id,"
                + " DATE_FORMAT(p.created_at, '%Y-%m-%d %H:%i:%s.%f') AS created_at_utc, " + MATCH + " AS relevance"
                + " FROM timeline_posts p" + where
                + " ORDER BY relevance DESC, p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset");
        params.forEach(query::setParameter);
        query.setParameter("limit", limit);
        query.setParameter("offset", offset);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<ScoredPost> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new ScoredPost(toLong(row[0]), text(row[1]), toLong(row[2]), text(row[3]), text(row[4]),
                    toLong(row[5]), text(row[6]), relevance(row[7])));
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
        StringBuilder sb = new StringBuilder(" WHERE ").append(MATCH)
                .append(" AND p.deleted_at IS NULL AND p.status = 'PUBLISHED' AND (")
                .append("(p.scope_type = 'TEAM' AND p.scope_id IN (:teamIds))")
                .append(" OR (p.scope_type = 'ORGANIZATION' AND p.scope_id IN (:orgIds))");
        params.put("keyword", c.booleanQuery());
        params.put("teamIds", c.teamIds());
        params.put("orgIds", c.orgIds());
        if (!c.scopeRestricted()) {
            sb.append(" OR p.scope_type = 'PUBLIC'")
                    // 配下配信: 距離1の祖先には CHILDREN/DESCENDANTS、距離2以上には DESCENDANTS のみ届く
                    .append(" OR (p.scope_type = 'ORGANIZATION' AND p.delivery_scope IN ('CHILDREN', 'DESCENDANTS')")
                    .append(" AND p.scope_id IN (:nearOrgIds))")
                    .append(" OR (p.scope_type = 'ORGANIZATION' AND p.delivery_scope = 'DESCENDANTS'")
                    .append(" AND p.scope_id IN (:farOrgIds))")
                    .append(" OR (p.scope_type = 'PERSONAL' AND p.user_id = :viewerUserId)");
            params.put("nearOrgIds", c.nearOrgIds());
            params.put("farOrgIds", c.farOrgIds());
            params.put("viewerUserId", c.viewerUserId());
        }
        sb.append(')');
        if (c.authorId() != null) {
            // チーム／組織として投稿したものは個人の投稿として扱わない（投稿者個人を推定させない）
            sb.append(" AND p.user_id = :authorId AND p.posted_as_type = 'USER'");
            params.put("authorId", c.authorId());
        }
        if (c.from() != null) {
            sb.append(" AND p.created_at >= CONVERT_TZ(CAST(:fromLocal AS DATETIME), :fromOffset, '+00:00')");
            params.put("fromLocal", c.from().localDateTime());
            params.put("fromOffset", c.from().utcOffset());
        }
        if (c.toExclusive() != null) {
            sb.append(" AND p.created_at < CONVERT_TZ(CAST(:toLocal AS DATETIME), :toOffset, '+00:00')");
            params.put("toLocal", c.toExclusive().localDateTime());
            params.put("toOffset", c.toExclusive().utcOffset());
        }
        return sb.toString();
    }
}
