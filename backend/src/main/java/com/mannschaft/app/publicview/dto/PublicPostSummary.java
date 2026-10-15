package com.mannschaft.app.publicview.dto;

import java.time.OffsetDateTime;

/**
 * F19.1 公開投稿一覧用の summary DTO。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.3 / §6.4</p>
 *
 * <p>本文を含まず、抜粋（excerpt）のみ保持する。詳細は別エンドポイント
 * （{@code GET /api/v1/public/{teams|organizations}/{id}/posts/{postId}}）で取得する。</p>
 *
 * <p>Phase 1 では {@code sourceType = "BLOG_POST"} のみが set される（軍議追補により
 * blog_posts のみが MVP 対応。timeline_posts / events は後続軍議で拡張）。</p>
 *
 * <h2>Defense in Depth - 禁則フィールド（絶対に含めない）</h2>
 * <ul>
 *   <li>{@code authorId} / {@code userId}（個人特定回避のため {@link PublicAuthorIdentity} 経由のみ）</li>
 *   <li>本文 / HTML（excerpt のみ。詳細は別エンドポイント）</li>
 *   <li>{@code visibility} / {@code status}（内部状態のため非公開）</li>
 * </ul>
 *
 * @param sourceType   投稿のソース種別（{@code "BLOG_POST"}、Phase 1 では本値のみ）
 * @param sourceId     投稿 ID
 * @param title        投稿タイトル
 * @param excerpt      投稿の抜粋（200 文字程度）
 * @param author       段階開示済みの投稿者識別
 * @param scope        所属スコープ（チーム / 組織）
 * @param publishedAt  公開日時
 */
public record PublicPostSummary(
        String sourceType,
        Long sourceId,
        String title,
        String excerpt,
        PublicAuthorIdentity author,
        PublicScopeRef scope,
        OffsetDateTime publishedAt
) {

    /** 投稿ソース種別: ブログ記事。 */
    public static final String SOURCE_TYPE_BLOG_POST = "BLOG_POST";
}
