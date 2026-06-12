package com.mannschaft.app.team.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.organization.exception.OrganizationNotFoundException;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.team.dto.TeamSearchCriteria;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.service.TeamSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link OrganizationTeamSearchController} の MockMvc 結合テスト（F15.4 Phase 1）。
 *
 * <p>設計書 {@code docs/features/F15.4_team_store_search_within_org.md §3.4} のステータスコード網羅:</p>
 * <ul>
 *   <li>200: 正常検索（空結果 / 非空結果）</li>
 *   <li>400: keyword 過長 / size 範囲外 / sort 不正</li>
 *   <li>404: 存在しない組織 / PRIVATE 組織への未ログインアクセス</li>
 * </ul>
 *
 * <p>DTO 切替（{@link com.mannschaft.app.team.dto.TeamPublicSummaryResponse} /
 * {@link com.mannschaft.app.team.dto.TeamSearchResultResponse}）も検証する。</p>
 */
@WebMvcTest(OrganizationTeamSearchController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("OrganizationTeamSearchController 結合テスト")
class OrganizationTeamSearchControllerTest {

    /** URL に使うスラッグ（列挙攻撃対策で URL 用スラッグを採用）*/
    private static final String ORG_SLUG = "test-org-01";
    /** 内部 BIGINT ID（Service 呼び出しに使用）*/
    private static final Long ORG_ID = 100L;
    /** 存在しない組織のスラッグ（404 テスト用）*/
    private static final String UNKNOWN_ORG_SLUG = "unknown-org-99";
    private static final Long MEMBER_USER_ID = 500L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TeamSearchService teamSearchService;

    @MockitoBean
    private com.mannschaft.app.organization.service.OrganizationService organizationService;

    @MockitoBean
    private AccessControlService accessControlService;

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
        // Controller が resolveOrgId を先に呼ぶため、全テストで共通 mock を設定
        given(organizationService.resolveOrgId(eq(ORG_SLUG))).willReturn(ORG_ID);
    }

    // ════════════════════════════════════════════════════════════
    // 200: 正常検索
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /search 200 未ログイン: 抑制版 DTO（TeamPublicSummaryResponse）が返る")
    void search_anonymous_returnsPublicSummary() throws Exception {
        SecurityContextHolder.clearContext();

        TeamEntity team = TeamEntity.builder()
                .name("公開店舗A")
                .nameKana("こうかいてんぽえー")
                .prefecture("東京都")
                .city("渋谷区")
                .template("salon")
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(true)
                .iconUrl("https://cdn/icon.png")
                .bannerUrl("https://cdn/banner.png")
                .memberCount(42L)
                .build();
        // F22.1: 構造化地域コードを併存返却
        team.updateRegionCodes("13", "13113");
        Page<TeamEntity> page = new PageImpl<>(List.of(team));
        given(teamSearchService.search(eq(ORG_ID), any(TeamSearchCriteria.class), any(), any(Pageable.class)))
                .willReturn(page);
        // 未ログインなのでメンバー判定は呼ばれないが、安全側に false を返しておく
        given(accessControlService.isMember(any(), eq(ORG_ID), eq("ORGANIZATION")))
                .willReturn(false);

        mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/teams/search", ORG_SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("公開店舗A"))
                .andExpect(jsonPath("$.data[0].prefecture").value("東京都"))
                .andExpect(jsonPath("$.data[0].city").value("渋谷区"))
                // F22.1: camelCase の地域コードが併存して返る
                .andExpect(jsonPath("$.data[0].prefectureCode").value("13"))
                .andExpect(jsonPath("$.data[0].cityCode").value("13113"))
                // 抑制版には visibility / bannerUrl / supporterEnabled / memberCount は含まれない
                .andExpect(jsonPath("$.data[0].visibility").doesNotExist())
                .andExpect(jsonPath("$.data[0].bannerUrl").doesNotExist())
                .andExpect(jsonPath("$.data[0].supporterEnabled").doesNotExist())
                .andExpect(jsonPath("$.data[0].memberCount").doesNotExist())
                .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    @DisplayName("GET /search 200 組織メンバー: 詳細版 DTO（TeamSearchResultResponse）が返る")
    void search_member_returnsDetailedResponse() throws Exception {
        setAuthenticated(MEMBER_USER_ID);

        TeamEntity team = TeamEntity.builder()
                .name("詳細店舗B")
                .nameKana("しょうさいてんぽびー")
                .prefecture("大阪府")
                .city("梅田")
                .template("clinic")
                .visibility(TeamEntity.Visibility.GUESTS_AND_ABOVE)
                .supporterEnabled(false)
                .iconUrl("https://cdn/icon2.png")
                .bannerUrl("https://cdn/banner2.png")
                .memberCount(17L)
                .build();
        // F22.1: 構造化地域コード
        team.updateRegionCodes("27", "27100");
        Page<TeamEntity> page = new PageImpl<>(List.of(team));
        given(teamSearchService.search(eq(ORG_ID), any(TeamSearchCriteria.class), eq(MEMBER_USER_ID), any(Pageable.class)))
                .willReturn(page);
        given(accessControlService.isMember(eq(MEMBER_USER_ID), eq(ORG_ID), eq("ORGANIZATION")))
                .willReturn(true);

        mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/teams/search", ORG_SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("詳細店舗B"))
                // 詳細版には visibility / bannerUrl / supporterEnabled / memberCount が含まれる
                .andExpect(jsonPath("$.data[0].visibility").value("GUESTS_AND_ABOVE"))
                .andExpect(jsonPath("$.data[0].bannerUrl").value("https://cdn/banner2.png"))
                .andExpect(jsonPath("$.data[0].supporterEnabled").value(false))
                .andExpect(jsonPath("$.data[0].memberCount").value(17))
                // F22.1: camelCase の地域コードが併存して返る
                .andExpect(jsonPath("$.data[0].prefectureCode").value("27"))
                .andExpect(jsonPath("$.data[0].cityCode").value("27100"));
    }

    @Test
    @DisplayName("GET /search 200 空結果: data=[] / meta.total=0")
    void search_emptyResult() throws Exception {
        given(teamSearchService.search(eq(ORG_ID), any(TeamSearchCriteria.class), any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/teams/search", ORG_SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.total").value(0));
    }

    // ════════════════════════════════════════════════════════════
    // 400: バリデーション違反
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /search 400: keyword 101 文字（上限超過）")
    void search_keywordTooLong_400() throws Exception {
        String tooLong = "あ".repeat(101);

        mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/teams/search", ORG_SLUG)
                        .param("keyword", tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("keyword too long")));
    }

    @Test
    @DisplayName("GET /search 400: size=51（上限超過）")
    void search_sizeOverMax_400() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/teams/search", ORG_SLUG)
                        .param("size", "51"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /search 400: size=0（下限未満）")
    void search_sizeUnderMin_400() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/teams/search", ORG_SLUG)
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /search 400: page=-1（負数）")
    void search_negativePage_400() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/teams/search", ORG_SLUG)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /search 400: sort=不正値（ホワイトリスト外）")
    void search_invalidSort_400() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/teams/search", ORG_SLUG)
                        .param("sort", "deletedAt,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /search 400: sort 方向が不正")
    void search_invalidSortDirection_400() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/teams/search", ORG_SLUG)
                        .param("sort", "name,sideways"))
                .andExpect(status().isBadRequest());
    }

    // ════════════════════════════════════════════════════════════
    // 404: NotFound
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /search 404: 存在しない組織 ID")
    void search_unknownOrg_404() throws Exception {
        willThrow(new OrganizationNotFoundException())
                .given(organizationService).resolveOrgId(eq(UNKNOWN_ORG_SLUG));

        mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/teams/search", UNKNOWN_ORG_SLUG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.error").value("Organization not found"));
    }

    @Test
    @DisplayName("GET /search 404: PRIVATE 組織への未ログインアクセス（エニュメレーション対策）")
    void search_privateOrgAnonymous_404() throws Exception {
        SecurityContextHolder.clearContext();
        willThrow(new OrganizationNotFoundException())
                .given(organizationService).resolveOrgId(eq(ORG_SLUG));

        mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/teams/search", ORG_SLUG))
                .andExpect(status().isNotFound())
                // 内部状態（PRIVATE / 削除済み）を漏らさない固定メッセージ
                .andExpect(jsonPath("$.data.error").value("Organization not found"));
    }

    // ════════════════════════════════════════════════════════════
    // クエリパラメータの引き渡し検証
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GET /search クエリパラメータ全種類が Criteria に正しく渡る")
    void search_criteriaPassThrough() throws Exception {
        org.mockito.ArgumentCaptor<TeamSearchCriteria> captor =
                org.mockito.ArgumentCaptor.forClass(TeamSearchCriteria.class);

        given(teamSearchService.search(eq(ORG_ID), captor.capture(), any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/teams/search", ORG_SLUG)
                        .param("keyword", "整体")
                        .param("prefecture", "東京都")
                        .param("city", "渋谷区")
                        .param("template", "salon")
                        .param("prefectureCode", "13")
                        .param("cityCode", "13113")
                        .param("page", "2")
                        .param("size", "10")
                        .param("sort", "createdAt,desc"))
                .andExpect(status().isOk());

        TeamSearchCriteria criteria = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(criteria.keyword()).isEqualTo("整体");
        org.assertj.core.api.Assertions.assertThat(criteria.prefecture()).isEqualTo("東京都");
        org.assertj.core.api.Assertions.assertThat(criteria.city()).isEqualTo("渋谷区");
        org.assertj.core.api.Assertions.assertThat(criteria.template()).isEqualTo("salon");
        // F22.1: 地域コードのクエリパラメータが Criteria に渡る（既存の流儀＝camelCase param 名）
        org.assertj.core.api.Assertions.assertThat(criteria.prefectureCode()).isEqualTo("13");
        org.assertj.core.api.Assertions.assertThat(criteria.cityCode()).isEqualTo("13113");
    }

    @Test
    @DisplayName("GET /search code クエリのみ指定でも Criteria に code が渡る（dual-support）")
    void search_codeOnlyPassThrough() throws Exception {
        org.mockito.ArgumentCaptor<TeamSearchCriteria> captor =
                org.mockito.ArgumentCaptor.forClass(TeamSearchCriteria.class);

        given(teamSearchService.search(eq(ORG_ID), captor.capture(), any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/teams/search", ORG_SLUG)
                        .param("prefectureCode", "13")
                        .param("cityCode", "13113"))
                .andExpect(status().isOk());

        TeamSearchCriteria criteria = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(criteria.prefectureCode()).isEqualTo("13");
        org.assertj.core.api.Assertions.assertThat(criteria.cityCode()).isEqualTo("13113");
        // 名称は未指定
        org.assertj.core.api.Assertions.assertThat(criteria.prefecture()).isNull();
        org.assertj.core.api.Assertions.assertThat(criteria.city()).isNull();
    }

    // ────────────────────────────────────────────────────────────
    // ヘルパー
    // ────────────────────────────────────────────────────────────

    private void setAuthenticated(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }
}
