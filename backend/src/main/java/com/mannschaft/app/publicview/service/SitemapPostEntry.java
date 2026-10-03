package com.mannschaft.app.publicview.service;

import java.time.LocalDateTime;

/**
 * sitemap.xml 生成用: 投稿エントリ。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2</p>
 *
 * @param scopeId   チーム ID または組織 ID
 * @param scopeSlug 親スコープの slug。組織投稿だけが持つ（組織の公開ページ URL は
 *                  {@code /public/organizations/{slug}/posts/{postId}}。F01.2.1 AC-A13）。チーム投稿は null
 * @param postId    投稿 ID
 * @param lastMod   最終更新日時（{@code <lastmod>} タグ用）
 */
public record SitemapPostEntry(Long scopeId, String scopeSlug, Long postId, LocalDateTime lastMod) {

    /** 親スコープの slug を持たないエントリ（チーム投稿）。 */
    public SitemapPostEntry(Long scopeId, Long postId, LocalDateTime lastMod) {
        this(scopeId, null, postId, lastMod);
    }
}