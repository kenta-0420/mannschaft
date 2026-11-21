package com.mannschaft.app.bulletin.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * グローバル検索（F04.6 THREAD 種別）の掲示板スレッドキーワード検索クエリ（CMP-261116-0722 PR-4）。
 *
 * <p><b>可視性述語</b>（件数と取得で同一の WHERE 句を使う）。次の<b>すべて</b>を満たすスレッドだけを返す:</p>
 * <ol>
 *   <li>{@code scope_type} と {@code scope_id} を<b>必ずペアで</b>照合する:
 *       {@code TEAM} は {@code scope_id} が閲覧者の所属チーム、{@code ORGANIZATION} は閲覧者の所属組織。
 *       PERSONAL / VILLAGE / TOURNAMENT / TOURNAMENT_DIVISION は {@code scope_id} が他種別の ID と衝突しても
 *       どの句にも該当しない。所属は集約側 {@code SearchViewer}（GUEST のみ・削除済み・アーカイブ済みスコープ除外済み）。</li>
 *   <li>{@code deleted_at IS NULL} かつ {@code is_archived = FALSE}。</li>
 * </ol>
 * <p>返信（{@code bulletin_replies}）は検索対象にしない（設計書 §7 は title / body のみ）。</p>
 *
 * <p><b>時刻列</b>: 期間指定は閲覧者 TZ の壁時計とオフセットを渡し、SQL の {@code CONVERT_TZ} で UTC 格納基準へ
 * 変換してから比較する。値はすべてバインドし、所属スコープ数は IN 句のパラメータ 1 個に収まる。</p>
 */
@Repository
public class BulletinThreadKeywordSearchRepository {

    private static final String MATCH = "MATCH(t.title, t.body) AGAINST(:keyword IN BOOLEAN MODE)";

    @PersistenceContext
    private EntityManager em;

    /**
     * 検索条件（IN 句用の集合は空にしないこと）。
     *
     * @param booleanQuery FULLTEXT BOOLEAN MODE の検索式（空にしないこと）
     * @param teamIds      母集団の所属チーム ID（空ならダミー値）
     * @param orgIds       母集団の所属組織 ID（空ならダミー値）
     * @param authorId     投稿者（null なら絞らない）
     * @param from         期間開始（null なら絞らない）
     * @param toExclusive  期間終了の翌日開始（null なら絞らない）
     */
    public record Condition(String booleanQuery, List<Long> teamIds, List<Long> orgIds, Long authorId,
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
     * @param id           スレッド ID
     * @param title        題名
     * @param body         本文
     * @param authorId     作成者（null 可）
     * @param scopeType    スコープ種別名（TEAM / ORGANIZATION）
     * @param scopeId      スコープ ID
     * @param createdAtUtc 作成日時の DB 格納値（UTC 壁時計、{@code yyyy-MM-dd HH:mm:ss.SSSSSS}。null 可）
     * @param relevance    FULLTEXT の関連度
     */
    public record ScoredThread(Long id, String title, String body, Long authorId, String scopeType, Long scopeId,
                               String createdAtUtc, double relevance) {
    }

    /** 可視スレッドの件数（{@link #find} と同一の WHERE 句）。 */
    public long count(Condition condition) {
        Map<String, Object> params = new HashMap<>();
        String where = where(condition, params);
        Query query = em.createNativeQuery("SELECT COUNT(*) FROM bulletin_threads t" + where);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * 可視スレッドを関連度→新しい順に 1 ページ分取得する（{@link #count} と同一の WHERE 句）。
     *
     * <p>結果に載せる列は、可視性を判定した<b>同じ SQL の SELECT 列</b>から取る。ID だけを取って別クエリで行を
     * 読み直すと、その間にスコープ移動等と本文の更新が同時にコミットされた場合に、判定時とは別の状態の内容を返して
     * しまう（CMP-261116-0722 PR-6 検分 P1 の横展開）。作成日時は DB の UTC 格納値を文字列で返す。</p>
     */
    public List<ScoredThread> find(Condition condition, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = where(condition, params);
        Query query = em.createNativeQuery("SELECT t.id, t.title, t.body, t.author_id, t.scope_type, t.scope_id,"
                + " DATE_FORMAT(t.created_at, '%Y-%m-%d %H:%i:%s.%f') AS created_at_utc, " + MATCH + " AS relevance"
                + " FROM bulletin_threads t" + where
                + " ORDER BY relevance DESC, t.created_at DESC, t.id DESC LIMIT :limit OFFSET :offset");
        params.forEach(query::setParameter);
        query.setParameter("limit", limit);
        query.setParameter("offset", offset);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<ScoredThread> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new ScoredThread(toLong(row[0]), text(row[1]), text(row[2]), toLong(row[3]), text(row[4]),
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
                .append(" AND t.deleted_at IS NULL AND t.is_archived = FALSE")
                // 種別と ID は必ずペアで照合する（TOURNAMENT 等の scope_id が他種別の ID と衝突しても拾わない）
                .append(" AND ((t.scope_type = 'TEAM' AND t.scope_id IN (:teamIds))")
                .append(" OR (t.scope_type = 'ORGANIZATION' AND t.scope_id IN (:orgIds)))");
        params.put("keyword", c.booleanQuery());
        params.put("teamIds", c.teamIds());
        params.put("orgIds", c.orgIds());
        if (c.authorId() != null) {
            sb.append(" AND t.author_id = :authorId");
            params.put("authorId", c.authorId());
        }
        if (c.from() != null) {
            sb.append(" AND t.created_at >= CONVERT_TZ(CAST(:fromLocal AS DATETIME), :fromOffset, '+00:00')");
            params.put("fromLocal", c.from().localDateTime());
            params.put("fromOffset", c.from().utcOffset());
        }
        if (c.toExclusive() != null) {
            sb.append(" AND t.created_at < CONVERT_TZ(CAST(:toLocal AS DATETIME), :toOffset, '+00:00')");
            params.put("toLocal", c.toExclusive().localDateTime());
            params.put("toOffset", c.toExclusive().utcOffset());
        }
        return sb.toString();
    }
}
