package com.mannschaft.app.publicview.dto;

import java.time.LocalDateTime;

/**
 * F19.1 Phase 4 公開組織検索 API のレスポンス DTO。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §7.x Phase 4</p>
 *
 * <h2>Defense in Depth - 禁則フィールド（絶対に含めない）</h2>
 * <ul>
 *   <li>メンバー一覧 / 氏名 / メール / 電話 / 番地レベル住所</li>
 *   <li>{@code supporterEnabled} / {@code archivedAt} / {@code deletedAt} / {@code version}</li>
 *   <li>出席情報 / チャット履歴 / ファイル / 内部ドキュメント</li>
 * </ul>
 *
 * @param id           組織 ID
 * @param slug         組織スラッグ（URL ルーティング用。{@code /organizations/{slug}} に使用する）
 * @param name         組織名
 * @param iconUrl      アイコン URL（null 可）
 * @param memberCount  所属ユーザー数（{@code user_roles} からの集計値）
 * @param lastPostDate 最新投稿日時（{@code blog_posts.created_at} MAX、投稿なしの場合は null）
 */
public record PublicOrganizationSearchResultResponse(
        Long id,
        String slug,
        String name,
        String iconUrl,
        int memberCount,
        LocalDateTime lastPostDate
) {}
