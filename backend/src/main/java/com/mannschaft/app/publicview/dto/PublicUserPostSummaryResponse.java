package com.mannschaft.app.publicview.dto;

import java.time.LocalDateTime;

/**
 * F19.1 Phase 6: 公開ユーザーの投稿サマリー用レスポンス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.6 Phase 6</p>
 *
 * <p>エンドポイント {@code GET /api/v1/public/users/{userId}/posts} の
 * ページ要素として返却される。</p>
 *
 * <p>{@code visibility = PUBLIC} かつ {@code status = PUBLISHED} かつ
 * {@code public_visible = true} の投稿のみ含む。</p>
 *
 * @param postId    投稿 ID
 * @param title     投稿タイトル
 * @param scopeType スコープ種別（"TEAM" または "ORGANIZATION"）
 * @param scopeName スコープ名（チーム名 / 組織名）
 * @param scopeId   スコープ ID（チーム ID / 組織 ID の文字列表現、リンク生成用）
 * @param createdAt 投稿作成日時
 */
public record PublicUserPostSummaryResponse(
        Long postId,
        String title,
        String scopeType,
        String scopeName,
        String scopeId,
        LocalDateTime createdAt
) {}
