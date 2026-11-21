package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityStatus;
import com.mannschaft.app.search.SearchLikeConditions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * グローバル検索（F04.6 ACTIVITY 種別）の活動記録キーワード検索クエリ（CMP-261116-0722 PR-7）。
 *
 * <p><b>可視性述語</b>（件数と取得で同一の WHERE 句を使う）。次の<b>すべて</b>を満たす活動記録だけを返す:</p>
 * <ol>
 *   <li>{@code deleted_at IS NULL} かつ {@code status = 'PUBLISHED'}（DRAFT は作成者本人・ADMIN を含め誰にも出さない）。</li>
 *   <li>{@code title} が全語を含む（LIKE。語は {@link SearchLikeConditions} でエスケープしてバインドし、
 *       {@code %} {@code _} をワイルドカードとして解釈させない）。</li>
 *   <li>{@link ScopeGroup} のいずれかに該当する: {@code scope_type} と {@code scope_id} を必ずペアで照合し
 *       （TEAM / ORGANIZATION のみ。COMMITTEE はどの句にも該当しない）、かつ {@code visibility} がそのグループの
 *       可視レベルに含まれる。</li>
 *   <li>手動記録（{@code is_auto_generated_from_schedule = FALSE}）か、呼び出し側が元予定の可視性を一括判定して
 *       許可した自動生成記録 ID（{@link Condition#allowedAutomaticIds()}）に含まれる。</li>
 * </ol>
 *
 * <p><b>TOCTOU 回避</b>: {@link #find} は、可視性を判定したのと同じ 1 本の SQL の SELECT 列で返却データ
 * （題名・説明・スコープ・作成者・作成日時）を取得する。ID を取ってから ID で再取得しない（判定後に記録が
 * 変更・削除・移動されても、判定を通っていない値を返さない）。</p>
 *
 * <p><b>時刻列</b>: 期間指定は閲覧者 TZ の壁時計とオフセットを渡し、SQL の {@code CONVERT_TZ} で UTC 格納基準へ
 * 変換してから {@code created_at} と比較する（Java の時刻値は束縛しない。番人 {@code RawSqlTimeColumnGuardTest}）。
 * 作成日時は {@code created_at} の DB 格納値（UTC 壁時計）を {@code DATE_FORMAT} で文字列として読み、呼び出し側が
 * {@code SearchHit#createdAtOfStoredUtc} で瞬間へ直す（セッション TZ にも JDBC の時刻変換にも依存しない。
 * 他の検索種別と同じ方式）。値はすべてバインドする。</p>
 */
@Repository
public class ActivityResultKeywordSearchRepository {

    private static final List<String> COLUMNS = List.of("ar.title");

    /** 自動生成記録の許可 ID が空のときに IN 句へ入れる番兵（ID は正の値のみなので決して一致しない）。 */
    static final long NO_MATCH_ID = -1L;

    @PersistenceContext
    private EntityManager em;

    /**
     * スコープに依らない共通条件。
     *
     * @param terms       検索語（未エスケープ。AND。空にしないこと）
     * @param authorId    作成者（{@code created_by}。null なら絞らない）
     * @param from        期間開始（null なら絞らない）
     * @param toExclusive 期間終了の翌日開始（null なら絞らない）
     */
    public record BaseCondition(List<String> terms, Long authorId, DateBound from, DateBound toExclusive) {
    }

    /**
     * 同じ可視レベル集合を持つ同種スコープの束。
     *
     * @param scopeType    {@link ActivityScopeType#TEAM} か {@link ActivityScopeType#ORGANIZATION}
     * @param scopeIds     スコープ ID（空にしないこと）
     * @param visibilities そのスコープで閲覧できる visibility 名（空にしないこと）
     */
    public record ScopeGroup(ActivityScopeType scopeType, List<Long> scopeIds, List<String> visibilities) {
    }

    /**
     * 検索条件。
     *
     * @param base                共通条件
     * @param groups              スコープのグループ（空なら SQL を発行しないこと）
     * @param allowedAutomaticIds 元予定の可視性判定を通った自動生成記録 ID（空可。空なら自動生成記録は出ない）
     */
    public record Condition(BaseCondition base, List<ScopeGroup> groups, Set<Long> allowedAutomaticIds) {
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
     * 元予定の可視性判定に使う、キーワードに一致した自動生成記録の参照。
     *
     * @param id         活動記録 ID
     * @param scopeType  記録のスコープ種別名
     * @param scopeId    記録のスコープ ID
     * @param scheduleId 元予定 ID（null 可）
     */
    public record AutomaticReference(Long id, String scopeType, Long scopeId, Long scheduleId) {
    }

    /**
     * 1 件分の取得結果（可視性を判定した同じ SQL の SELECT 列）。
     *
     * @param id          活動記録 ID
     * @param scopeType   スコープ種別名（TEAM / ORGANIZATION）
     * @param scopeId     スコープ ID
     * @param title       題名
     * @param description 説明（null 可）
     * @param createdBy    作成者（null 可）
     * @param createdAtUtc 作成日時の DB 格納値（UTC 壁時計、{@code yyyy-MM-dd HH:mm:ss.SSSSSS}。null 可）
     */
    public record ActivityRow(Long id, String scopeType, Long scopeId, String title, String description,
                              Long createdBy, String createdAtUtc) {
    }

    /**
     * キーワード・共通条件・スコープ条件（可視レベル含む）を満たす公開済みの自動生成記録の参照を列挙する
     * （元予定の可視性は呼び出し側が schedule の公開 Service で一括判定する）。
     */
    public List<AutomaticReference> findAutomaticReferences(BaseCondition base, List<ScopeGroup> groups) {
        Map<String, Object> params = new HashMap<>();
        String sql = "SELECT ar.id, ar.scope_type, ar.scope_id, ar.schedule_id FROM activity_results ar"
                + candidateWhere(base, groups, params) + " AND ar.is_auto_generated_from_schedule = TRUE";
        Query query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<AutomaticReference> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new AutomaticReference(toLong(row[0]), String.valueOf(row[1]), toLong(row[2]),
                    toLong(row[3])));
        }
        return result;
    }

    /** 可視記録の件数（{@link #find} と同一の WHERE 句）。 */
    public long count(Condition condition) {
        Map<String, Object> params = new HashMap<>();
        String sql = "SELECT COUNT(*) FROM activity_results ar" + visibleWhere(condition, params);
        Query query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    /** 可視記録を新しい順に 1 ページ分取得する（{@link #count} と同一の WHERE 句。返却値も同じ SQL で取得する）。 */
    public List<ActivityRow> find(Condition condition, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String sql = "SELECT ar.id, ar.scope_type, ar.scope_id, ar.title, ar.description, ar.created_by,"
                + " DATE_FORMAT(ar.created_at, '%Y-%m-%d %H:%i:%s.%f') AS created_at_utc"
                + " FROM activity_results ar" + visibleWhere(condition, params)
                + " ORDER BY ar.created_at DESC, ar.id DESC LIMIT :limit OFFSET :offset";
        Query query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        query.setParameter("limit", limit);
        query.setParameter("offset", offset);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<ActivityRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new ActivityRow(toLong(row[0]), String.valueOf(row[1]), toLong(row[2]),
                    text(row[3]), text(row[4]), toLong(row[5]), text(row[6])));
        }
        return result;
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /** 本体の述語: 候補条件 ＋（手動記録 OR 許可済み自動生成記録）。 */
    private static String visibleWhere(Condition c, Map<String, Object> params) {
        Set<Long> allowed = c.allowedAutomaticIds();
        params.put("allowedAutomaticIds", allowed == null || allowed.isEmpty() ? List.of(NO_MATCH_ID)
                : List.copyOf(allowed));
        return candidateWhere(c.base(), c.groups(), params)
                + " AND (ar.is_auto_generated_from_schedule = FALSE OR ar.id IN (:allowedAutomaticIds))";
    }

    /** 候補条件（未削除・公開済み・題名一致・スコープと可視レベル・作成者・期間）。 */
    private static String candidateWhere(BaseCondition b, List<ScopeGroup> groups, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE ar.deleted_at IS NULL AND ar.status = :published");
        params.put("published", ActivityStatus.PUBLISHED.name());
        if (b.terms().isEmpty()) {
            // 呼び出し側は語なしで SQL を発行しない約束だが、万一来ても何も返さない（安全側）
            sb.append(" AND 1 = 0");
        }
        sb.append(SearchLikeConditions.allTermsContain(COLUMNS, b.terms(), params));
        List<String> scopes = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            ScopeGroup g = groups.get(i);
            if (g.scopeType() != ActivityScopeType.TEAM && g.scopeType() != ActivityScopeType.ORGANIZATION) {
                continue;
            }
            scopes.add("(ar.scope_type = :scopeType" + i + " AND ar.scope_id IN (:scopeIds" + i + ")"
                    + " AND ar.visibility IN (:visibilities" + i + "))");
            params.put("scopeType" + i, g.scopeType().name());
            params.put("scopeIds" + i, g.scopeIds());
            params.put("visibilities" + i, g.visibilities());
        }
        if (scopes.isEmpty()) {
            // 呼び出し側は空条件で SQL を発行しない約束だが、万一来ても何も返さない（安全側）
            sb.append(" AND 1 = 0");
        } else {
            sb.append(" AND (").append(String.join(" OR ", scopes)).append(")");
        }
        if (b.authorId() != null) {
            sb.append(" AND ar.created_by = :authorId");
            params.put("authorId", b.authorId());
        }
        if (b.from() != null) {
            sb.append(" AND ar.created_at >= CONVERT_TZ(CAST(:fromLocal AS DATETIME), :fromOffset, '+00:00')");
            params.put("fromLocal", b.from().localDateTime());
            params.put("fromOffset", b.from().utcOffset());
        }
        if (b.toExclusive() != null) {
            sb.append(" AND ar.created_at < CONVERT_TZ(CAST(:toLocal AS DATETIME), :toOffset, '+00:00')");
            params.put("toLocal", b.toExclusive().localDateTime());
            params.put("toOffset", b.toExclusive().utcOffset());
        }
        return sb.toString();
    }
}
