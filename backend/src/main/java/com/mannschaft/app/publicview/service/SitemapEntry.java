package com.mannschaft.app.publicview.service;

import java.time.LocalDateTime;

/**
 * sitemap.xml 生成用: チーム / 組織エントリ。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2</p>
 *
 * @param id      エンティティ ID
 * @param lastMod 最終更新日時（{@code <lastmod>} タグ用）
 */
public record SitemapEntry(Long id, LocalDateTime lastMod) {
}
