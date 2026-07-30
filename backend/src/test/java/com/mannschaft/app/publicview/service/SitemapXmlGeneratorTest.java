package com.mannschaft.app.publicview.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SitemapXmlGenerator} ユニットテスト（F19.1 Phase 3）。
 *
 * <p>テスト対象:</p>
 * <ul>
 *   <li>urlset 形式（50,000 URL 以下）の XML 構造</li>
 *   <li>sitemapindex 形式（50,001 URL 以上）の XML 構造</li>
 *   <li>URL パターン（/public/teams/{id}, /public/organizations/{id}/posts/{postId} 等）</li>
 *   <li>XML インジェクション防止（escapeXml）</li>
 *   <li>lastmod の存在と ISO 日付フォーマット</li>
 *   <li>generatePage によるページ分割</li>
 * </ul>
 */
@DisplayName("SitemapXmlGenerator テスト (F19.1 Phase 3)")
class SitemapXmlGeneratorTest {

    private static final String BASE_URL = "https://mannschaft.example";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 19, 12, 0);

    private final SitemapXmlGenerator generator = new SitemapXmlGenerator();

    // ────────────────────────────────────────────────────────────
    // urlset 形式（50,000 URL 以下）
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("urlset 形式（50,000 URL 以下）")
    class UrlsetFormat {

        @Test
        @DisplayName("URL が 0 件の場合、空の urlset を返す")
        void generate_emptyEntries_returnsEmptyUrlset() {
            String xml = generator.generate(BASE_URL,
                    List.of(), List.of(), List.of(), List.of(), List.of());

            assertThat(xml).contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            assertThat(xml).contains("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
            assertThat(xml).contains("</urlset>");
            assertThat(xml).doesNotContain("<url>");
        }

        @Test
        @DisplayName("チームエントリが urlset に含まれる")
        void generate_teamEntries_containsTeamUrls() {
            List<SitemapEntry> teams = List.of(
                    new SitemapEntry(1L, NOW),
                    new SitemapEntry(2L, NOW)
            );

            String xml = generator.generate(BASE_URL, teams, List.of(), List.of(), List.of(), List.of());

            assertThat(xml).contains("<urlset");
            assertThat(xml).contains(BASE_URL + "/public/teams/1");
            assertThat(xml).contains(BASE_URL + "/public/teams/2");
            assertThat(xml).contains("<lastmod>2026-05-19</lastmod>");
        }

        @Test
        @DisplayName("組織エントリが urlset に含まれる")
        void generate_orgEntries_containsOrgUrls() {
            List<SitemapEntry> orgs = List.of(
                    new SitemapEntry(10L, NOW)
            );

            String xml = generator.generate(BASE_URL, List.of(), orgs, List.of(), List.of(), List.of());

            assertThat(xml).contains("<urlset");
            assertThat(xml).contains(BASE_URL + "/public/organizations/10");
        }

        @Test
        @DisplayName("チーム投稿エントリが urlset に含まれる")
        void generate_teamPostEntries_containsTeamPostUrls() {
            List<SitemapPostEntry> teamPosts = List.of(
                    new SitemapPostEntry(1L, 100L, NOW)
            );

            String xml = generator.generate(BASE_URL, List.of(), List.of(), teamPosts, List.of(), List.of());

            assertThat(xml).contains("<urlset");
            assertThat(xml).contains(BASE_URL + "/public/teams/1/posts/100");
        }

        @Test
        @DisplayName("組織投稿エントリが urlset に含まれる")
        void generate_orgPostEntries_containsOrgPostUrls() {
            List<SitemapPostEntry> orgPosts = List.of(
                    new SitemapPostEntry(10L, 200L, NOW)
            );

            String xml = generator.generate(BASE_URL, List.of(), List.of(), List.of(), orgPosts, List.of());

            assertThat(xml).contains("<urlset");
            assertThat(xml).contains(BASE_URL + "/public/organizations/10/posts/200");
        }

        @Test
        @DisplayName("F06.4 活動記録エントリが /activity/{id} として urlset に含まれる")
        void generate_activityEntries_containsActivityUrls() {
            List<SitemapEntry> activities = List.of(
                    new SitemapEntry(42L, NOW),
                    new SitemapEntry(43L, NOW)
            );

            String xml = generator.generate(
                    BASE_URL, List.of(), List.of(), List.of(), List.of(), activities);

            assertThat(xml).contains("<urlset");
            // 公開活動記録は URL にスコープを含まない（SNS シェア用の ID 直引きが正準）。
            assertThat(xml).contains("<loc>" + BASE_URL + "/activity/42</loc>");
            assertThat(xml).contains("<loc>" + BASE_URL + "/activity/43</loc>");
            assertThat(xml).contains("<lastmod>2026-05-19</lastmod>");
        }

        @Test
        @DisplayName("活動記録は他の 4 種と併存し、URL 形式が混ざらない")
        void generate_activitiesAlongsideOtherEntries_keepsUrlShapes() {
            String xml = generator.generate(
                    BASE_URL,
                    List.of(new SitemapEntry(1L, NOW)),
                    List.of(new SitemapEntry(10L, NOW)),
                    List.of(new SitemapPostEntry(1L, 100L, NOW)),
                    List.of(new SitemapPostEntry(10L, 200L, NOW)),
                    List.of(new SitemapEntry(42L, NOW)));

            assertThat(xml).contains("<loc>" + BASE_URL + "/public/teams/1</loc>");
            assertThat(xml).contains("<loc>" + BASE_URL + "/public/organizations/10</loc>");
            assertThat(xml).contains("<loc>" + BASE_URL + "/public/teams/1/posts/100</loc>");
            assertThat(xml).contains("<loc>" + BASE_URL + "/public/organizations/10/posts/200</loc>");
            assertThat(xml).contains("<loc>" + BASE_URL + "/activity/42</loc>");
            // 活動記録がスコープ配下 URL として出ていないこと（URL 形式の取り違え防止）
            assertThat(xml).doesNotContain("/public/teams/42");
            assertThat(xml).doesNotContain("/activity/1</loc>");
        }

        @Test
        @DisplayName("活動記録も 50,000 URL 上限の計算対象に入る（分割から漏れない）")
        void generate_activitiesCountTowardSplitThreshold() {
            List<SitemapEntry> teams = new ArrayList<>();
            for (int i = 1; i <= SitemapXmlGenerator.MAX_URLS_PER_SITEMAP; i++) {
                teams.add(new SitemapEntry((long) i, NOW));
            }
            // 上限ちょうどのチーム + 活動記録 1 件 = 50,001 → sitemapindex に切り替わるはず。
            String xml = generator.generate(BASE_URL, teams, List.of(), List.of(), List.of(),
                    List.of(new SitemapEntry(42L, NOW)));

            assertThat(xml).contains("<sitemapindex");
            assertThat(xml).doesNotContain("<urlset");
        }

        @Test
        @DisplayName("lastmod が null の場合、<lastmod> タグを出力しない")
        void generate_nullLastmod_omitsLastmodTag() {
            List<SitemapEntry> teams = List.of(new SitemapEntry(1L, null));

            String xml = generator.generate(BASE_URL, teams, List.of(), List.of(), List.of(), List.of());

            assertThat(xml).contains(BASE_URL + "/public/teams/1");
            assertThat(xml).doesNotContain("<lastmod>");
        }

        @Test
        @DisplayName("50,000 件のエントリでも urlset 形式を返す")
        void generate_exactly50000Entries_returnsUrlsetFormat() {
            List<SitemapEntry> teams = new ArrayList<>();
            for (int i = 1; i <= SitemapXmlGenerator.MAX_URLS_PER_SITEMAP; i++) {
                teams.add(new SitemapEntry((long) i, NOW));
            }

            String xml = generator.generate(BASE_URL, teams, List.of(), List.of(), List.of(), List.of());

            assertThat(xml).contains("<urlset");
            assertThat(xml).doesNotContain("<sitemapindex");
        }
    }

    // ────────────────────────────────────────────────────────────
    // sitemapindex 形式（50,001 URL 以上）
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sitemapindex 形式（50,001 URL 以上）")
    class SitemapIndexFormat {

        @Test
        @DisplayName("50,001 件で sitemapindex 形式を返す")
        void generate_over50000Entries_returnsSitemapindexFormat() {
            List<SitemapEntry> teams = new ArrayList<>();
            for (int i = 1; i <= SitemapXmlGenerator.MAX_URLS_PER_SITEMAP + 1; i++) {
                teams.add(new SitemapEntry((long) i, NOW));
            }

            String xml = generator.generate(BASE_URL, teams, List.of(), List.of(), List.of(), List.of());

            assertThat(xml).contains("<sitemapindex");
            assertThat(xml).doesNotContain("<urlset");
            assertThat(xml).contains(BASE_URL + "/sitemap-1.xml");
            assertThat(xml).contains(BASE_URL + "/sitemap-2.xml");
        }

        @Test
        @DisplayName("100,001 件で 3 ページ分の sitemapindex を返す")
        void generate_over100000Entries_returns3PageSitemapindex() {
            List<SitemapEntry> teams = new ArrayList<>();
            for (int i = 1; i <= SitemapXmlGenerator.MAX_URLS_PER_SITEMAP * 2 + 1; i++) {
                teams.add(new SitemapEntry((long) i, NOW));
            }

            String xml = generator.generate(BASE_URL, teams, List.of(), List.of(), List.of(), List.of());

            assertThat(xml).contains("<sitemapindex");
            assertThat(xml).contains(BASE_URL + "/sitemap-1.xml");
            assertThat(xml).contains(BASE_URL + "/sitemap-2.xml");
            assertThat(xml).contains(BASE_URL + "/sitemap-3.xml");
        }
    }

    // ────────────────────────────────────────────────────────────
    // generatePage
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generatePage によるページ分割")
    class GeneratePage {

        @Test
        @DisplayName("page=1 は先頭 50,000 件を返す")
        void generatePage_page1_returnsFirst50000Entries() {
            List<SitemapEntry> teams = new ArrayList<>();
            for (int i = 1; i <= SitemapXmlGenerator.MAX_URLS_PER_SITEMAP + 1; i++) {
                teams.add(new SitemapEntry((long) i, NOW));
            }

            String xml = generator.generatePage(BASE_URL, teams, List.of(), List.of(), List.of(), List.of(), 1);

            assertThat(xml).contains("<urlset");
            assertThat(xml).contains(BASE_URL + "/public/teams/1");
            // 50,001 件目は page=2
            assertThat(xml).doesNotContain(BASE_URL + "/public/teams/" + (SitemapXmlGenerator.MAX_URLS_PER_SITEMAP + 1));
        }

        @Test
        @DisplayName("page=2 は後続エントリを返す")
        void generatePage_page2_returnsSubsequentEntries() {
            List<SitemapEntry> teams = new ArrayList<>();
            for (int i = 1; i <= SitemapXmlGenerator.MAX_URLS_PER_SITEMAP + 1; i++) {
                teams.add(new SitemapEntry((long) i, NOW));
            }

            String xml = generator.generatePage(BASE_URL, teams, List.of(), List.of(), List.of(), List.of(), 2);

            assertThat(xml).contains("<urlset");
            assertThat(xml).contains(
                    BASE_URL + "/public/teams/" + (SitemapXmlGenerator.MAX_URLS_PER_SITEMAP + 1));
        }

        @Test
        @DisplayName("範囲外のページ番号は空の urlset を返す")
        void generatePage_outOfRange_returnsEmptyUrlset() {
            List<SitemapEntry> teams = List.of(new SitemapEntry(1L, NOW));

            String xml = generator.generatePage(BASE_URL, teams, List.of(), List.of(), List.of(), List.of(), 99);

            assertThat(xml).contains("<urlset");
            assertThat(xml).doesNotContain("<url>");
        }
    }

    // ────────────────────────────────────────────────────────────
    // XML インジェクション防止
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("XML インジェクション防止")
    class XmlInjectionPrevention {

        @Test
        @DisplayName("& がエスケープされる")
        void escapeXml_ampersand_isEscaped() {
            assertThat(SitemapXmlGenerator.escapeXml("a&b")).isEqualTo("a&amp;b");
        }

        @Test
        @DisplayName("< がエスケープされる")
        void escapeXml_lessThan_isEscaped() {
            assertThat(SitemapXmlGenerator.escapeXml("<tag>")).isEqualTo("&lt;tag&gt;");
        }

        @Test
        @DisplayName("> がエスケープされる")
        void escapeXml_greaterThan_isEscaped() {
            assertThat(SitemapXmlGenerator.escapeXml("a>b")).isEqualTo("a&gt;b");
        }

        @Test
        @DisplayName("\" がエスケープされる")
        void escapeXml_doubleQuote_isEscaped() {
            assertThat(SitemapXmlGenerator.escapeXml("\"value\"")).isEqualTo("&quot;value&quot;");
        }

        @Test
        @DisplayName("' がエスケープされる")
        void escapeXml_singleQuote_isEscaped() {
            assertThat(SitemapXmlGenerator.escapeXml("it's")).isEqualTo("it&apos;s");
        }

        @Test
        @DisplayName("null は空文字を返す")
        void escapeXml_null_returnsEmpty() {
            assertThat(SitemapXmlGenerator.escapeXml(null)).isEqualTo("");
        }

        @Test
        @DisplayName("特殊文字なしの URL はそのまま出力される")
        void generate_normalUrl_notEscaped() {
            List<SitemapEntry> teams = List.of(new SitemapEntry(1L, NOW));

            String xml = generator.generate(BASE_URL, teams, List.of(), List.of(), List.of(), List.of());

            assertThat(xml).contains("<loc>" + BASE_URL + "/public/teams/1</loc>");
        }
    }
}
