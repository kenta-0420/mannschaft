package com.mannschaft.app.publicview.service;

import java.time.LocalDateTime;

/**
 * sitemap.xml 生成用: チーム / 組織 / 活動記録エントリ。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2</p>
 *
 * @param id      エンティティ ID
 * @param slug    公開ページ URL 用の slug。組織エントリだけが持つ（組織の公開ページ URL は slug に一本化。
 *                F01.2.1 AC-A13）。チーム・活動記録は null
 * @param lastMod 最終更新日時（{@code <lastmod>} タグ用）
 */
public record SitemapEntry(Long id, String slug, LocalDateTime lastMod) {

    /** slug を持たないエントリ（チーム・活動記録）。 */
    public SitemapEntry(Long id, LocalDateTime lastMod) {
        this(id, null, lastMod);
    }
}