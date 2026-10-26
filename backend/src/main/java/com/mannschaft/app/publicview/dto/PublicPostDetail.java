package com.mannschaft.app.publicview.dto;

import java.time.OffsetDateTime;

/**
 * F19.1 公開投稿詳細用の DTO。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.3 / §4.3</p>
 *
 * <p>{@code bodyHtml} は HTML サニタイズ済み（既存 BlogPostService が担保する前提）。
 * Phase 1 では blog_posts の {@code body} カラムをそのまま返すが、フロント側で
 * dompurify 等の二段サニタイズを行うこと。</p>
 *
 * <h2>Defense in Depth - 禁則フィールド（絶対に含めない）</h2>
 * <ul>
 *   <li>{@code authorId} / {@code userId}</li>
 *   <li>{@code rejectionReason} / {@code previewToken} / {@code visibility} / {@code status}</li>
 *   <li>{@code version} / {@code archivedAt} / {@code deletedAt}</li>
 * </ul>
 *
 * @param sourceType   投稿のソース種別（{@code "BLOG_POST"}）
 * @param sourceId     投稿 ID
 * @param title        投稿タイトル
 * @param bodyHtml     投稿本文（HTML サニタイズ済み）
 * @param author       段階開示済みの投稿者識別
 * @param scope        所属スコープ（チーム / 組織）
 * @param publishedAt  公開日時
 */
public record PublicPostDetail(
        String sourceType,
        Long sourceId,
        String title,
        String bodyHtml,
        PublicAuthorIdentity author,
        PublicScopeRef scope,
        OffsetDateTime publishedAt
) {
}
