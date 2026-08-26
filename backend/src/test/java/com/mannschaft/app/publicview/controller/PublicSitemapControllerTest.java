package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.service.SitemapEntry;
import com.mannschaft.app.publicview.service.SitemapPostEntry;
import com.mannschaft.app.publicview.service.SitemapQueryService;
import com.mannschaft.app.publicview.service.SitemapXmlGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link PublicSitemapController} MockMvc 結合テスト（F19.1 Phase 3）。
 *
 * <p>テスト対象:</p>
 * <ul>
 *   <li>GET /sitemap.xml — 200 + application/xml + Cache-Control: max-age=3600</li>
 *   <li>GET /robots.txt — 200 + text/plain + /sitemap.xml 行を含む</li>
 *   <li>未認証でアクセス可能か（addFilters=false で SecurityConfig をバイパス）</li>
 *   <li>XML の基本構造（urlset タグ、URL パターン）</li>
 * </ul>
 */
@WebMvcTest(PublicSitemapController.class)
@Import(SitemapXmlGenerator.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "app.base-url=https://mannschaft.example")
@DisplayName("PublicSitemapController 結合テスト (F19.1 Phase 3)")
class PublicSitemapControllerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 19, 12, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SitemapQueryService sitemapQueryService;

    // WebMvcTest が要求する依存の最小モック注入
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        // デフォルトのモック設定
        given(sitemapQueryService.findPublicTeamEntries())
                .willReturn(List.of(new SitemapEntry(1L, NOW)));
        given(sitemapQueryService.findPublicOrganizationEntries())
                .willReturn(List.of(new SitemapEntry(10L, NOW)));
        given(sitemapQueryService.findPublicTeamPostEntries())
                .willReturn(List.of(new SitemapPostEntry(1L, 100L, NOW)));
        given(sitemapQueryService.findPublicOrganizationPostEntries())
                .willReturn(List.of(new SitemapPostEntry(10L, 200L, NOW)));
        // F06.4: 公開活動記録も sitemap に収録される
        given(sitemapQueryService.findPublicActivityEntries())
                .willReturn(List.of(new SitemapEntry(42L, NOW)));
    }

    // ────────────────────────────────────────────────────────────
    // GET /sitemap.xml
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /sitemap.xml 200: application/xml を返す")
    void getSitemap_returns200WithXmlContentType() throws Exception {
        mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"));
    }

    @Test
    @DisplayName("GET /sitemap.xml Cache-Control: max-age=3600 が付与される")
    void getSitemap_hasCacheControlMaxAge3600() throws Exception {
        mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=3600")));
    }

    @Test
    @DisplayName("GET /sitemap.xml — urlset タグを含む XML が返る")
    void getSitemap_returnsUrlsetXml() throws Exception {
        String responseBody = mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(responseBody)
                .contains("<urlset")
                .contains("https://mannschaft.example/public/teams/1")
                .contains("https://mannschaft.example/public/organizations/10")
                .contains("https://mannschaft.example/public/teams/1/posts/100")
                .contains("https://mannschaft.example/public/organizations/10/posts/200")
                // F06.4: 公開活動記録は /activity/{id}（スコープを含まない ID 直引き URL）
                .contains("https://mannschaft.example/activity/42");
    }

    @Test
    @DisplayName("未認証でも GET /sitemap.xml に到達できる（addFilters=false 確認）")
    void getSitemap_anonymousUser_canAccess() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /sitemap.xml — XML 宣言が含まれる")
    void getSitemap_containsXmlDeclaration() throws Exception {
        String responseBody = mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(responseBody)
                .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    }

    // ────────────────────────────────────────────────────────────
    // GET /robots.txt
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /robots.txt 200: text/plain を返す")
    void getRobotsTxt_returns200WithTextPlainContentType() throws Exception {
        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"));
    }

    @Test
    @DisplayName("GET /robots.txt Cache-Control: max-age=86400 が付与される")
    void getRobotsTxt_hasCacheControlMaxAge86400() throws Exception {
        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=86400")));
    }

    @Test
    @DisplayName("GET /robots.txt — Sitemap 行と Disallow 行が含まれる")
    void getRobotsTxt_containsSitemapAndDisallowLines() throws Exception {
        String responseBody = mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(responseBody)
                .contains("Sitemap: https://mannschaft.example/sitemap.xml")
                .contains("Disallow: /api/v1/")
                .contains("Allow: /api/v1/public/")
                .contains("User-agent: *");
    }

    @Test
    @DisplayName("未認証でも GET /robots.txt に到達できる（addFilters=false 確認）")
    void getRobotsTxt_anonymousUser_canAccess() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk());
    }

    // ────────────────────────────────────────────────────────────
    // GET /sitemap-{page}.xml
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /sitemap-1.xml 200: application/xml を返す")
    void getSitemapPage_returns200WithXmlContentType() throws Exception {
        mockMvc.perform(get("/sitemap-1.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"));
    }

    @Test
    @DisplayName("GET /sitemap-1.xml — urlset タグを含む XML が返る")
    void getSitemapPage_returnsUrlsetXml() throws Exception {
        String responseBody = mockMvc.perform(get("/sitemap-1.xml"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(responseBody)
                .contains("<urlset")
                .contains("https://mannschaft.example/public/teams/1");
    }
}
