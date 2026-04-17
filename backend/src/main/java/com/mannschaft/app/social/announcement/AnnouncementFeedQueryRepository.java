package com.mannschaft.app.social.announcement;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

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
 *   <li>visibility に応じた閲覧範囲フィルタ（WHERE 句に組み込み、Service 層の if 文に依存しない）</li>
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
     * visibility によって閲覧可能なレコードを絞り込む:
     * <ul>
     *   <li>{@code "MEMBERS_ONLY"} — {@code visibility = 'MEMBERS_ONLY'} のみ（メンバーロール）</li>
     *   <li>{@code "SUPPORTERS_AND_ABOVE"} — {@code visibility IN ('MEMBERS_ONLY', 'SUPPORTERS_AND_ABOVE')}（メンバー・サポーター両方）</li>
     *   <li>その他の値 — 全件（ADMIN・SYSTEM_ADMIN 用。実運用では "MEMBERS_ONLY" を渡すこと）</li>
     * </ul>
     * </p>
     *
     * @param scopeType  スコープ種別（TEAM または ORGANIZATION）
     * @param scopeId    スコープ ID
     * @param visibility 閲覧者のロールに応じた visibility 指定値
     * @param cursor     カーソル（この ID 未満のレコードを取得。null の場合は先頭から）
     * @param limit      取得件数（次ページ有無の判定のため limit + 1 件を取得すること）
     * @return お知らせフィードリスト（ピン留め優先 → 新着順）
     */
    public List<AnnouncementFeedEntity> findByScope(
            AnnouncementScopeType scopeType,
            Long scopeId,
            String visibility,
            Long cursor,
            int limit) {

        StringBuilder jpql = new StringBuilder("""
                SELECT a FROM AnnouncementFeedEntity a
                WHERE a.scopeType = :scopeType
                  AND a.scopeId = :scopeId
                  AND (a.expiresAt IS NULL OR a.expiresAt > CURRENT_TIMESTAMP)
                  AND a.sourceDeletedAt IS NULL
                """);

        // visibility フィルタ: Service 層の if 文に依存せず WHERE 句で完結させる（設計書 6.2 章）
        if ("SUPPORTERS_AND_ABOVE".equals(visibility)) {
            // メンバーおよびサポーターが閲覧可能なレコード
            jpql.append("  AND a.visibility IN ('MEMBERS_ONLY', 'SUPPORTERS_AND_ABOVE')\n");
        } else if ("MEMBERS_ONLY".equals(visibility)) {
            // メンバーのみが閲覧可能なレコード（SUPPORTER には返さない）
            jpql.append("  AND a.visibility = 'MEMBERS_ONLY'\n");
        }
        // それ以外（'PUBLIC' 等）はフィルタなし（全件対象）

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

        if (cursor != null) {
            query.setParameter("cursor", cursor);
        }

        query.setMaxResults(limit);
        return query.getResultList();
    }
}
