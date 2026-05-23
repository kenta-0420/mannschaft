package com.mannschaft.app.publicview.dto;

import java.time.OffsetDateTime;

/**
 * F19.1 Phase 7 公開タイムライン投稿レスポンス DTO。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.2 Phase 7</p>
 *
 * <p><strong>Defense in Depth — 禁則フィールド（絶対に含めない）</strong></p>
 * <ul>
 *   <li>{@code userId} / {@code authorId}（個人特定回避のため不含）</li>
 *   <li>リアクション数・リプライ数等の内部統計（未ログイン公開不要）</li>
 *   <li>{@code deletedAt} / {@code status}（内部状態は非公開）</li>
 *   <li>{@code authorRealNameSnapshot}（PII 漏洩防止）</li>
 * </ul>
 *
 * @param id          投稿 ID
 * @param content     投稿本文（200 文字程度のトリミング済み）
 * @param scopeRef    所属スコープ（チーム / 組織）
 * @param createdAt   投稿日時
 */
public record PublicTimelinePostResponse(
        Long id,
        String content,
        PublicScopeRef scopeRef,
        OffsetDateTime createdAt
) {
}
