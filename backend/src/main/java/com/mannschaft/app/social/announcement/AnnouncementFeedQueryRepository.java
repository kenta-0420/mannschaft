package com.mannschaft.app.social.announcement;

import jakarta.persistence.EntityManager;
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

        StringBuilder jpql = new StringBuilder("""
                SELECT a FROM AnnouncementFeedEntity a
                WHERE a.scopeType = :scopeType
                  AND a.scopeId = :scopeId
                  AND (a.expiresAt IS NULL OR a.expiresAt > CURRENT_TIMESTAMP)
                  AND a.sourceDeletedAt IS NULL
                  AND a.visibility IN :allowedVisibilities
                """);

        // カーソルページング: 指定 ID 未満のレコードを取得
        if (cursor != null) {
            jpql.append("  AND a.id < :cursor\n");
        }

        // ピン留め優先 → 新着順
        jpql.append("ORDER BY a.isPinned DESC, a.createdAt DESC");

        TypedQuery<AnnouncementFeedEntity> query =
                em.createQuery(jpql.toString(), AnnouncementFeedEntity.class);
        query.setParameter("scopeType", scopeType);
        query.setParameter("scopeId", scopeId);
        query.setParameter("allowedVisibilities", allowedVisibilities);

        if (cursor != null) {
            query.setParameter("cursor", cursor);
        }

        query.setMaxResults(limit);
        return query.getResultList();
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
