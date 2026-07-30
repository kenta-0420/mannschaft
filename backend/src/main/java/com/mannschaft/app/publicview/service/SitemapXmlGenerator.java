package com.mannschaft.app.publicview.service;

import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * F19.1 Phase 3 sitemap.xml 生成コンポーネント。
 *
 * <p>50,000 URL / sitemap 上限に対応し、超過時は {@code sitemapindex} 形式で分割する。
 * XML 生成は外部ライブラリなし（StringBuilder 手組み）で実施する。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2</p>
 */
@Component
public class SitemapXmlGenerator {

    /** sitemap 1 件あたりの最大 URL 数（Google 上限）。 */
    static final int MAX_URLS_PER_SITEMAP = 50_000;

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    private static final String URLSET_NS = "http://www.sitemaps.org/schemas/sitemap/0.9";
    private static final String SITEMAPINDEX_NS = "http://www.sitemaps.org/schemas/sitemap/0.9";

    /**
     * メインの sitemap.xml XML 文字列を生成する。
     *
     * <p>エントリ総数が {@link #MAX_URLS_PER_SITEMAP} 以下なら通常の {@code urlset} 形式、
     * 超過なら {@code sitemapindex} 形式（{@code /sitemap-1.xml}, {@code /sitemap-2.xml} …）
     * を返す。</p>
     *
     * @param baseUrl    フロントエンドのベース URL（例: {@code https://mannschaft.example}）
     * @param teams      PUBLIC チームエントリ
     * @param orgs       PUBLIC 組織エントリ
     * @param teamPosts  PUBLIC チーム投稿エントリ
     * @param orgPosts   PUBLIC 組織投稿エントリ
     * @param activities PUBLIC 活動記録エントリ（F06.4）
     * @return sitemap XML 文字列
     */
    public String generate(String baseUrl,
                           List<SitemapEntry> teams,
                           List<SitemapEntry> orgs,
                           List<SitemapPostEntry> teamPosts,
                           List<SitemapPostEntry> orgPosts,
                           List<SitemapEntry> activities) {

        List<String[]> urlEntries = buildUrlEntries(baseUrl, teams, orgs, teamPosts, orgPosts, activities);

        if (urlEntries.size() <= MAX_URLS_PER_SITEMAP) {
            return buildUrlsetXml(urlEntries);
        } else {
            int pageCount = (int) Math.ceil((double) urlEntries.size() / MAX_URLS_PER_SITEMAP);
            return buildSitemapIndexXml(baseUrl, pageCount);
        }
    }

    /**
     * 分割された個別 sitemap ページ（例: /sitemap-1.xml）の XML 文字列を生成する。
     *
     * @param baseUrl    フロントエンドのベース URL
     * @param teams      PUBLIC チームエントリ
     * @param orgs       PUBLIC 組織エントリ
     * @param teamPosts  PUBLIC チーム投稿エントリ
     * @param orgPosts   PUBLIC 組織投稿エントリ
     * @param activities PUBLIC 活動記録エントリ（F06.4）
     * @param page       ページ番号（1始まり）
     * @return 指定ページの urlset XML 文字列。ページ番号が範囲外の場合は空の urlset。
     */
    public String generatePage(String baseUrl,
                               List<SitemapEntry> teams,
                               List<SitemapEntry> orgs,
                               List<SitemapPostEntry> teamPosts,
                               List<SitemapPostEntry> orgPosts,
                               List<SitemapEntry> activities,
                               int page) {
        List<String[]> urlEntries = buildUrlEntries(baseUrl, teams, orgs, teamPosts, orgPosts, activities);
        int fromIndex = (page - 1) * MAX_URLS_PER_SITEMAP;
        if (fromIndex >= urlEntries.size()) {
            return buildUrlsetXml(List.of());
        }
        int toIndex = Math.min(fromIndex + MAX_URLS_PER_SITEMAP, urlEntries.size());
        return buildUrlsetXml(urlEntries.subList(fromIndex, toIndex));
    }

    // ────────────────────────────────────────────────────────────
    // 内部ヘルパ
    // ────────────────────────────────────────────────────────────

    /**
     * チーム・組織・投稿エントリから URL エントリのリストを構築する。
     * 各要素は {@code [loc, lastmod]} の 2 要素配列。
     */
    List<String[]> buildUrlEntries(String baseUrl,
                                   List<SitemapEntry> teams,
                                   List<SitemapEntry> orgs,
                                   List<SitemapPostEntry> teamPosts,
                                   List<SitemapPostEntry> orgPosts,
                                   List<SitemapEntry> activities) {
        List<String[]> entries = new ArrayList<>();

        for (SitemapEntry t : teams) {
            entries.add(new String[]{
                    baseUrl + "/public/teams/" + t.id(),
                    formatDate(t)
            });
        }
        for (SitemapEntry o : orgs) {
            entries.add(new String[]{
                    baseUrl + "/public/organizations/" + o.id(),
                    formatDate(o)
            });
        }
        for (SitemapPostEntry tp : teamPosts) {
            entries.add(new String[]{
                    baseUrl + "/public/teams/" + tp.scopeId() + "/posts/" + tp.postId(),
                    formatDate(tp)
            });
        }
        for (SitemapPostEntry op : orgPosts) {
            entries.add(new String[]{
                    baseUrl + "/public/organizations/" + op.scopeId() + "/posts/" + op.postId(),
                    formatDate(op)
            });
        }
        // F06.4 公開活動記録。他の公開ページと違い URL にスコープを含まない
        // （SNS シェア用の ID 直引き経路 /activity/{id} が正準。ActivitySharePanel が配る URL と同一）。
        for (SitemapEntry a : activities) {
            entries.add(new String[]{
                    baseUrl + "/activity/" + a.id(),
                    formatDate(a)
            });
        }
        return entries;
    }

    /**
     * {@code urlset} 形式の XML 文字列を組み立てる。
     *
     * <p>URL に含まれる特殊文字（{@code &}, {@code <}, {@code >}, {@code '}, {@code "}）は
     * XML エスケープを施す（XML インジェクション防止）。</p>
     */
    String buildUrlsetXml(List<String[]> urlEntries) {
        StringBuilder sb = new StringBuilder(512 + urlEntries.size() * 100);
        sb.append(XML_DECLARATION);
        sb.append("<urlset xmlns=\"").append(URLSET_NS).append("\">\n");
        for (String[] entry : urlEntries) {
            sb.append("  <url>\n");
            sb.append("    <loc>").append(escapeXml(entry[0])).append("</loc>\n");
            if (entry[1] != null && !entry[1].isEmpty()) {
                sb.append("    <lastmod>").append(escapeXml(entry[1])).append("</lastmod>\n");
            }
            sb.append("  </url>\n");
        }
        sb.append("</urlset>");
        return sb.toString();
    }

    /**
     * {@code sitemapindex} 形式の XML 文字列を組み立てる。
     */
    String buildSitemapIndexXml(String baseUrl, int pageCount) {
        StringBuilder sb = new StringBuilder(256 + pageCount * 80);
        sb.append(XML_DECLARATION);
        sb.append("<sitemapindex xmlns=\"").append(SITEMAPINDEX_NS).append("\">\n");
        for (int i = 1; i <= pageCount; i++) {
            sb.append("  <sitemap>\n");
            sb.append("    <loc>").append(escapeXml(baseUrl + "/sitemap-" + i + ".xml")).append("</loc>\n");
            sb.append("  </sitemap>\n");
        }
        sb.append("</sitemapindex>");
        return sb.toString();
    }

    private String formatDate(SitemapEntry entry) {
        if (entry.lastMod() == null) {
            return "";
        }
        return entry.lastMod().format(ISO_DATE);
    }

    private String formatDate(SitemapPostEntry entry) {
        if (entry.lastMod() == null) {
            return "";
        }
        return entry.lastMod().format(ISO_DATE);
    }

    /**
     * XML 特殊文字をエスケープする（XML インジェクション防止）。
     */
    static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
