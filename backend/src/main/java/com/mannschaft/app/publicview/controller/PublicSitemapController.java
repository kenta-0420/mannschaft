package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.publicview.service.SitemapEntry;
import com.mannschaft.app.publicview.service.SitemapPostEntry;
import com.mannschaft.app.publicview.service.SitemapQueryService;
import com.mannschaft.app.publicview.service.SitemapXmlGenerator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * F19.1 Phase 3 SEO: 動的 sitemap.xml + robots.txt エンドポイント。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2 / §9.3</p>
 *
 * <ul>
 *   <li>{@code GET /sitemap.xml} — PUBLIC チーム・組織・投稿を収録した動的 sitemap。1h キャッシュ。</li>
 *   <li>{@code GET /sitemap-{page}.xml} — 50,000 URL 超過時の分割 sitemap。1h キャッシュ。</li>
 *   <li>{@code GET /robots.txt} — クローラ向けアクセス制御ファイル。24h キャッシュ。</li>
 * </ul>
 *
 * <p>いずれのエンドポイントも認証不要（{@code permitAll}）。
 * SecurityConfig で {@code /sitemap.xml}, {@code /sitemap-*.xml}, {@code /robots.txt} が
 * permitAll に追加されていること。</p>
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 3 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:247-248 — requestMatchers(GET, "/sitemap.xml", "/robots.txt")
 * / "/sitemap-*.xml" .permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * F19.1 Phase 3 SEO。sitemap / robots.txt は<b>検索エンジンのクローラが未認証で取得する</b>
 * のが仕様で、認証を課すと SEO が成立しない。収録するのは<b>公開ページの URL のみ</b>（非公開スコープは含めない）
 * 。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic
@RestController
@Tag(name = "SEO (F19.1 Phase 3)", description = "sitemap.xml / robots.txt エンドポイント")
@RequiredArgsConstructor
public class PublicSitemapController {

    private final SitemapQueryService sitemapQueryService;
    private final SitemapXmlGenerator sitemapXmlGenerator;

    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * 動的 sitemap.xml を返す。
     *
     * <p>PUBLIC チーム・組織・投稿のエントリを収録する。
     * 総 URL 数が 50,000 以下なら {@code urlset} 形式、超過なら {@code sitemapindex} 形式を返す。</p>
     *
     * <p>Cache-Control: public, max-age=3600 (1時間) を付与する。</p>
     */
    @GetMapping(value = "/sitemap.xml", produces = "application/xml;charset=UTF-8")
    @Operation(
            summary = "動的 sitemap.xml",
            description = "PUBLIC チーム・組織・投稿の URL を収録した sitemap。1時間キャッシュ。認証不要。")
    public ResponseEntity<String> getSitemap() {
        List<SitemapEntry> teams = sitemapQueryService.findPublicTeamEntries();
        List<SitemapEntry> orgs = sitemapQueryService.findPublicOrganizationEntries();
        List<SitemapPostEntry> teamPosts = sitemapQueryService.findPublicTeamPostEntries();
        List<SitemapPostEntry> orgPosts = sitemapQueryService.findPublicOrganizationPostEntries();

        String xml = sitemapXmlGenerator.generate(baseUrl, teams, orgs, teamPosts, orgPosts);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .contentType(MediaType.parseMediaType("application/xml;charset=UTF-8"))
                .body(xml);
    }

    /**
     * 分割 sitemap ページを返す（50,000 URL 超過時）。
     *
     * <p>例: {@code GET /sitemap-1.xml}, {@code GET /sitemap-2.xml}</p>
     *
     * <p>Cache-Control: public, max-age=3600 (1時間) を付与する。</p>
     *
     * @param page ページ番号（1始まり）
     */
    @GetMapping(value = "/sitemap-{page}.xml", produces = "application/xml;charset=UTF-8")
    @Operation(
            summary = "分割 sitemap ページ",
            description = "50,000 URL 超過時の分割 sitemap。page は 1 始まり。1時間キャッシュ。認証不要。")
    public ResponseEntity<String> getSitemapPage(@PathVariable int page) {
        List<SitemapEntry> teams = sitemapQueryService.findPublicTeamEntries();
        List<SitemapEntry> orgs = sitemapQueryService.findPublicOrganizationEntries();
        List<SitemapPostEntry> teamPosts = sitemapQueryService.findPublicTeamPostEntries();
        List<SitemapPostEntry> orgPosts = sitemapQueryService.findPublicOrganizationPostEntries();

        String xml = sitemapXmlGenerator.generatePage(baseUrl, teams, orgs, teamPosts, orgPosts, page);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .contentType(MediaType.parseMediaType("application/xml;charset=UTF-8"))
                .body(xml);
    }

    /**
     * robots.txt を返す。
     *
     * <p>クローラへのアクセス制御を定義する。
     * API パスは {@code /api/v1/} を Disallow し、公開 API {@code /api/v1/public/} を Allow する。</p>
     *
     * <p>Cache-Control: public, max-age=86400 (24時間) を付与する。</p>
     */
    @GetMapping(value = "/robots.txt", produces = "text/plain;charset=UTF-8")
    @Operation(
            summary = "robots.txt",
            description = "クローラ向けアクセス制御ファイル。24時間キャッシュ。認証不要。")
    public ResponseEntity<String> getRobotsTxt() {
        String content = "User-agent: *\n"
                + "Allow: /\n"
                + "Allow: /public/\n"
                + "Allow: /api/v1/public/\n"
                + "Disallow: /api/v1/\n"
                + "Disallow: /admin/\n"
                + "Disallow: /teams/\n"
                + "Disallow: /organizations/\n"
                + "\n"
                + "Sitemap: " + baseUrl + "/sitemap.xml\n";

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(24, TimeUnit.HOURS).cachePublic())
                .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .body(content);
    }
}
