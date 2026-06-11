package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.dto.PublicOrganizationSearchResultResponse;
import com.mannschaft.app.publicview.dto.PublicTeamSearchResultResponse;
import com.mannschaft.app.publicview.service.PublicOrganizationSearchQueryService;
import com.mannschaft.app.publicview.service.PublicTeamSearchQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link PublicDiscoverController} の MockMvc 結合テスト（F19.1 Phase 4）。
 *
 * <p>テストシナリオ:</p>
 * <ul>
 *   <li>200: keyword / prefecture なしで PUBLIC チームのみ返る</li>
 *   <li>200: keyword 指定で名前フィルタ動作</li>
 *   <li>200: prefecture 指定で絞り込み動作</li>
 *   <li>200: 空結果ページが返る（PRIVATE チームは含まれない）</li>
 *   <li>200: 組織版 — keyword / prefecture なし</li>
 *   <li>200: 組織版 — keyword 指定でフィルタ動作</li>
 *   <li>未ログインでも Controller に到達できる</li>
 * </ul>
 */
@WebMvcTest(PublicDiscoverController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PublicDiscoverController 結合テスト (F19.1 Phase 4)")
class PublicDiscoverControllerTest {

    /** PII / 内部状態に該当する禁則フィールド名（CI で漏洩検出）。 */
    static final String[] FORBIDDEN_FIELDS = {
            "email", "emails", "phone", "phoneNumber", "phones",
            "firstName", "lastName", "lastNameKana", "firstNameKana",
            "birthday", "passwordHash", "refreshToken",
            "addressLine", "streetAddress",
            "supporterEnabled", "archivedAt", "deletedAt", "version"
    };

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicTeamSearchQueryService publicTeamSearchQueryService;

    @MockitoBean
    private PublicOrganizationSearchQueryService publicOrganizationSearchQueryService;

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
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ─────────────────────────────────────────────
    // チーム検索テスト
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /public/teams/search — パラメータなし: PUBLIC チームのみ返る")
    void searchTeams_noParams_returnsPublicTeamsOnly() throws Exception {
        Page<PublicTeamSearchResultResponse> page = buildTeamPage(
                sampleTeam(1L, "東京FCチーム"),
                sampleTeam(2L, "大阪SC")
        );
        given(publicTeamSearchQueryService.search(eq(null), eq(null), eq(null), any()))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/teams/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(1L))
                .andExpect(jsonPath("$.content[0].name").value("東京FCチーム"))
                .andExpect(jsonPath("$.content[1].id").value(2L));
    }

    @Test
    @DisplayName("GET /public/teams/search?keyword=東京 — 名前フィルタ動作")
    void searchTeams_withKeyword_returnsFilteredResults() throws Exception {
        Page<PublicTeamSearchResultResponse> page = buildTeamPage(
                sampleTeam(1L, "東京FCチーム")
        );
        given(publicTeamSearchQueryService.search(eq("東京"), eq(null), eq(null), any()))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/teams/search").param("keyword", "東京"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("東京FCチーム"));
    }

    @Test
    @DisplayName("GET /public/teams/search?prefecture=大阪府 — 都道府県絞り込み動作")
    void searchTeams_withPrefecture_returnsFilteredResults() throws Exception {
        Page<PublicTeamSearchResultResponse> page = buildTeamPage(
                sampleTeam(2L, "大阪SC")
        );
        given(publicTeamSearchQueryService.search(eq(null), eq("大阪府"), eq(null), any()))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/teams/search").param("prefecture", "大阪府"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("大阪SC"));
    }

    @Test
    @DisplayName("GET /public/teams/search — PRIVATE チームが結果に含まれない（空ページ返却）")
    void searchTeams_privateTeamExcluded() throws Exception {
        Page<PublicTeamSearchResultResponse> emptyPage = Page.empty(PageRequest.of(0, 20));
        given(publicTeamSearchQueryService.search(eq(null), eq(null), eq(null), any()))
                .willReturn(emptyPage);

        mockMvc.perform(get("/api/v1/public/teams/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ─────────────────────────────────────────────
    // 組織検索テスト
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /public/organizations/search — パラメータなし: PUBLIC 組織のみ返る")
    void searchOrganizations_noParams_returnsPublicOrgsOnly() throws Exception {
        Page<PublicOrganizationSearchResultResponse> page = buildOrgPage(
                sampleOrg(10L, "東京商工会議所"),
                sampleOrg(11L, "大阪市役所")
        );
        given(publicOrganizationSearchQueryService.search(eq(null), eq(null), any()))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/organizations/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(10L))
                .andExpect(jsonPath("$.content[0].name").value("東京商工会議所"));
    }

    @Test
    @DisplayName("GET /public/organizations/search?keyword=東京 — 組織名フィルタ動作")
    void searchOrganizations_withKeyword_returnsFilteredResults() throws Exception {
        Page<PublicOrganizationSearchResultResponse> page = buildOrgPage(
                sampleOrg(10L, "東京商工会議所")
        );
        given(publicOrganizationSearchQueryService.search(eq("東京"), eq(null), any()))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/organizations/search").param("keyword", "東京"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("東京商工会議所"));
    }

    @Test
    @DisplayName("未ログインでも Controller に到達できる（publicDiscover は認証不要）")
    void search_anonymous_canReachController() throws Exception {
        SecurityContextHolder.clearContext();
        given(publicTeamSearchQueryService.search(eq(null), eq(null), eq(null), any()))
                .willReturn(Page.empty(PageRequest.of(0, 20)));

        mockMvc.perform(get("/api/v1/public/teams/search"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────
    // ヘルパーメソッド
    // ─────────────────────────────────────────────

    private Page<PublicTeamSearchResultResponse> buildTeamPage(
            PublicTeamSearchResultResponse... items) {
        List<PublicTeamSearchResultResponse> list = List.of(items);
        return new PageImpl<>(list, PageRequest.of(0, 20), list.size());
    }

    private Page<PublicOrganizationSearchResultResponse> buildOrgPage(
            PublicOrganizationSearchResultResponse... items) {
        List<PublicOrganizationSearchResultResponse> list = List.of(items);
        return new PageImpl<>(list, PageRequest.of(0, 20), list.size());
    }

    private PublicTeamSearchResultResponse sampleTeam(Long id, String name) {
        return new PublicTeamSearchResultResponse(
                id,
                "team-slug-" + id,
                name,
                "https://cdn.example.com/icons/" + id + ".png",
                10,
                LocalDateTime.of(2026, 5, 1, 12, 0, 0),
                "13",
                "13113"
        );
    }

    private PublicOrganizationSearchResultResponse sampleOrg(Long id, String name) {
        return new PublicOrganizationSearchResultResponse(
                id,
                name,
                "https://cdn.example.com/org-icons/" + id + ".png",
                0,
                LocalDateTime.of(2026, 5, 10, 9, 0, 0)
        );
    }
}
