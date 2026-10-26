package com.mannschaft.app.publicview.dto;

import java.time.OffsetDateTime;

/**
 * F19.1 Phase 6-B: 公開投稿コメントレスポンス DTO。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.7 Phase 6-B</p>
 *
 * <p><strong>PII 抑制</strong>: メールアドレス・本名（DISPLAY_NAME モード時）などの
 * 個人情報は含まない。表示名（{@code authorDisplayName}）のみ公開する。</p>
 *
 * @param commentId         コメント UUID（文字列形式）
 * @param authorId          投稿者ユーザー ID
 * @param authorDisplayName 投稿者の表示名
 * @param content           コメント本文
 * @param createdAt         作成日時
 */
public record PublicPostCommentResponse(
        String commentId,
        Long authorId,
        String authorDisplayName,
        String content,
        OffsetDateTime createdAt
) {
}
