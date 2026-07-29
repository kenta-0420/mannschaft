package com.mannschaft.app.social.announcement;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

/**
 * お知らせフィードのカーソルページングクエリリポジトリ（F02.6）。
 *
 * <p>
 * ウィジェット一覧取得専用の複合クエリを実装する。
 * {@link FriendFeedQueryRepository} のパターンを踏襲し、
 * {@link EntityManager} + JPQL でカーソルページングを実現する。
 * </p>
 *
 * <p>
 * <b>並び順</b>: ピン留め優先（{@code is_pinned DESC}）→ 新着順（{@code created_at DESC}）。
 * 優先度（URGENT → IMPORTANT → NORMAL）による並び替えは Service 層またはフロントエンドで実装する。
 * </p>
 *
 * <p>
 * <b>フィルタリング</b>:
 * <ul>
 *   <li>スコープ（{@code scope_type + scope_id}）で絞り込む</li>
 *   <li>期限切れ（{@code expires_at IS NULL OR expires_at > NOW()}）を除外する</li>
 *   <li>元コンテンツ削除済み（{@code source_deleted_at IS NULL}）を除外する</li>
 *   <li>閲覧者が閲覧できる visibility 集合での絞り込み（{@code visibility IN (許可集合)}・WHERE 句に組み込み、Service 層の if 文に依存しない）</li>
 *   <li>カーソル（{@code id < cursor}）でページング</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>戻り値件数</b>: {@code limit + 1} 件取得して次ページの有無を判定するのは呼び出し元（Service 層）の責務。
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class AnnouncementFeedQueryRepository {

    /**
     * 「そのスコープでその閲覧者に<b>可視な</b>お知らせ」を表す正準 WHERE 句（#2494）。
     *
     * <p><b>この定数が可視性判定の単一の真実源である。</b>
     * 一覧（{@link #findByScope}）と一括既読の対象抽出（{@link #findUnreadIdsByScope}）は
     * どちらもこの同一文字列を連結して JPQL を組み立てる。
     * 「一覧に出る集合＝既読にできる集合」という不変条件（裏目付第二陣 C-social / PR #2478）を
     * <b>規約ではなく構造で</b>担保するための措置であり、片方だけ条件を足す/落とすことが
     * 物理的にできない形にしてある。条件を変更する場合は必ずこの定数を変更すること
     * （個々のメソッド側に条件を書き足してはならない）。</p>
     *
     * <p>含まれる条件（順序も含めて 4 条件で全部）:</p>
     * <ul>
     *   <li>{@code scopeType + scopeId} — スコープ帰属</li>
     *   <li>{@code expiresAt IS NULL OR expiresAt > CURRENT_TIMESTAMP} — 期限切れ除外（<b>厳密な {@code >}</b>）</li>
     *   <li>{@code sourceDeletedAt IS NULL} — 元コンテンツ削除済み除外</li>
     *   <li>{@code visibility IN :allowedVisibilities} — 可視性</li>
     * </ul>
     */
    private static final String VISIBLE_IN_SCOPE_WHERE = """
            WHERE a.scopeType = :scopeType
              AND a.scopeId = :scopeId
              AND (a.expiresAt IS NULL OR a.expiresAt > CURRENT_TIMESTAMP)
              AND a.sourceDeletedAt IS NULL
              AND a.visibility IN :allowedVisibilities
            """;

    private final EntityManager em;

    /**
     * スコープ内のお知らせフィードをカーソルページングで取得する。
     *
     * <p>
     * <b>可視性フィルタ（F02.6 §6.2 漏洩根治）</b>:
     * 「閲覧者が閲覧できる visibility 集合」を {@code allowedVisibilities} で受け取り、
     * {@code visibility IN (許可集合)} で正しく絞り込む。集合は
     * {@link AnnouncementVisibility#allowedFor(String)} が算出する:
     * <ul>
     *   <li>SUPPORTER — {@code {PUBLIC, SUPPORTERS_AND_ABOVE}}（MEMBERS_AND_ABOVE は<b>含めない</b>）</li>
     *   <li>MEMBER 以上 — {@code {PUBLIC, SUPPORTERS_AND_ABOVE, MEMBERS_AND_ABOVE}}（3 種全部）</li>
     *   <li>未ログイン / ロールなし — {@code {PUBLIC}}</li>
     * </ul>
     * 従来の「単一 visibility 文字列」方式は SUPPORTER に MEMBERS_AND_ABOVE を露出させ、かつ
     * MEMBER 以上が PUBLIC/SUPPORTERS_AND_ABOVE を取りこぼす二重の欠陥があったため、
     * 「許可集合を渡す」方式へ再設計した。Service 層の if 文に依存せず WHERE 句で完結させる。
     * </p>
     *
     * @param scopeType           スコープ種別（TEAM または ORGANIZATION）
     * @param scopeId             スコープ ID
     * @param allowedVisibilities 閲覧者が閲覧できる visibility 値の集合（空集合・null は「該当なし＝空結果」）
     * @param cursor              カーソル（この ID 未満のレコードを取得。null の場合は先頭から）
     * @param limit               取得件数（次ページ有無の判定のため limit + 1 件を取得すること）
     * @return お知らせフィードリスト（ピン留め優先 → 新着順）
     */
    public List<AnnouncementFeedEntity> findByScope(
            AnnouncementScopeType scopeType,
            Long scopeId,
            Set<String> allowedVisibilities,
            Long cursor,
            int limit) {

        // 許可集合が空（=閲覧可能な visibility が一つもない）なら、症状を隠さず空結果を返す。
        if (allowedVisibilities == null || allowedVisibilities.isEmpty()) {
            return List.of();
        }

        StringBuilder jpql = new StringBuilder("SELECT a FROM AnnouncementFeedEntity a\n")
                .append(VISIBLE_IN_SCOPE_WHERE);

        // カーソルページング: 指定 ID 未満のレコードを取得
        if (cursor != null) {
            jpql.append("  AND a.id < :cursor\n");
        }

        // ピン留め優先 → 新着順
        jpql.append("ORDER BY a.isPinned DESC, a.createdAt DESC");

        TypedQuery<AnnouncementFeedEntity> query =
                em.createQuery(jpql.toString(), AnnouncementFeedEntity.class);
        bindVisibleInScopeParameters(query, scopeType, scopeId, allowedVisibilities);

        if (cursor != null) {
            query.setParameter("cursor", cursor);
        }

        query.setMaxResults(limit);
        return query.getResultList();
    }

    /**
     * スコープ内で「その閲覧者に可視」かつ「そのユーザーが<b>未読</b>」のお知らせ ID を
     * 件数上限つきで取得する（#2494 一括既読の対象抽出）。
     *
     * <p><b>なぜこのメソッドが要るのか</b>: 旧実装の一括既読は
     * {@code AnnouncementFeedRepository#findByScopeTypeAndScopeIdAndSourceDeletedAtIsNull} で
     * <b>スコープ内の feed を limit 無しで全件</b>取り、Java 側で可視性を絞ったうえ、
     * 既読済み ID の引き当てに全件を {@code IN} 句へ渡していた。長く運用されたスコープほど
     * プレースホルダ数・{@code INSERT} 件数が伸び、{@code max_allowed_packet} や
     * プリペアドステートメントのパラメータ上限に触れうるうえ、
     * <b>1 リクエストの実行時間がスコープの歴史の長さに比例</b>していた。
     * 「未読だけを DB 側で絞る」ことで、コストが<b>未読件数</b>にのみ比例するようになり、
     * feed ID の {@code IN} 句そのものが消える（残るパラメータは可視性集合の最大 3 個のみ）。</p>
     *
     * <p><b>可視性の 1 対 1</b>: WHERE 句は一覧クエリ {@link #findByScope} と<b>同一の定数</b>
     * {@code VISIBLE_IN_SCOPE_WHERE} を連結して組み立てる。差分は
     * 「未読のみ（{@code NOT EXISTS}）」「射影が ID のみ」「並び順が ID 昇順」の 3 点だけであり、
     * <b>可視性の条件は 1 文字も増減しない</b>（期限切れ境界が厳密な {@code >} であることを含む）。</p>
     *
     * @param scopeType           スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId             スコープ ID
     * @param allowedVisibilities 閲覧者が閲覧できる visibility 値の集合（空集合・null は「該当なし＝空結果」）
     * @param userId              既読判定の対象ユーザー ID
     * @param limit               1 回で取得する最大件数（チャンクサイズ）
     * @return 未読かつ可視なお知らせフィード ID のリスト（ID 昇順・最大 {@code limit} 件）
     */
    public List<Long> findUnreadIdsByScope(
            AnnouncementScopeType scopeType,
            Long scopeId,
            Set<String> allowedVisibilities,
            Long userId,
            int limit) {

        // 許可集合が空（=閲覧可能な visibility が一つもない）なら空結果。findByScope と同じ fail-closed。
        if (allowedVisibilities == null || allowedVisibilities.isEmpty()) {
            return List.of();
        }

        String jpql = "SELECT a.id FROM AnnouncementFeedEntity a\n"
                + VISIBLE_IN_SCOPE_WHERE
                + """
                  AND NOT EXISTS (
                      SELECT r.id FROM AnnouncementReadStatusEntity r
                      WHERE r.announcementFeedId = a.id
                        AND r.userId = :userId
                  )
                ORDER BY a.id ASC
                """;

        TypedQuery<Long> query = em.createQuery(jpql, Long.class);
        bindVisibleInScopeParameters(query, scopeType, scopeId, allowedVisibilities);
        query.setParameter("userId", userId);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    /**
     * {@code VISIBLE_IN_SCOPE_WHERE} が要求するバインドパラメータをまとめて設定する。
     * WHERE 句の定数と一体で保守すること（定数に条件を足したらここも足す）。
     */
    private void bindVisibleInScopeParameters(
            Query query,
            AnnouncementScopeType scopeType,
            Long scopeId,
            Set<String> allowedVisibilities) {
        query.setParameter("scopeType", scopeType);
        query.setParameter("scopeId", scopeId);
        query.setParameter("allowedVisibilities", allowedVisibilities);
    }

    /**
     * チームダッシュボード向けに、組織スコープのお知らせフィードを取得する。
     *
     * <p>target_team_ids のフィルタリングは Service 層（Java Stream）で行う。
     * {@code DashboardService.getTeamDashboard()} から呼び出される。</p>
     *
     * @param orgId               組織 ID
     * @param allowedVisibilities 閲覧者が閲覧できる visibility 値の集合
     * @param limit               取得上限件数（Java 層でフィルタするため多めに取得）
     * @return 組織スコープのお知らせフィードリスト（ピン留め優先 → 新着順）
     */
    public List<AnnouncementFeedEntity> findByOrgScopeForTeamDashboard(
            Long orgId, Set<String> allowedVisibilities, int limit) {
        return findByScope(AnnouncementScopeType.ORGANIZATION, orgId, allowedVisibilities, null, limit);
    }
}
