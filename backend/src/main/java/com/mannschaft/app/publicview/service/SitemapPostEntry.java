package com.mannschaft.app.publicview.service;

import java.time.LocalDateTime;

/**
 * sitemap.xml 生成用: 投稿エントリ。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2</p>
 *
 * @param scopeId チーム ID または組織 ID（URL の {scopeId} 部分）
 * @param postId  投稿 ID
 * @param lastMod 最終更新日時（{@code <lastmod>} タグ用）
 */
public record SitemapPostEntry(Long scopeId, Long postId, LocalDateTime lastMod) {
}
