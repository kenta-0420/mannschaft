package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.publicview.service.AdminPublicSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F19.1 Phase 7: AdminPublicSettingsController の MockMvc テスト。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.8 Phase 7</p>
 *
 * <p>テストケース:</p>
 * <ul>
 *   <li>PS_001: チーム public-settings PATCH → 204 NoContent</li>
 *   <li>PS_002: 組織 public-settings PATCH → 204 NoContent</li>
 *   <li>PS_003: 存在しないチーム → 404 Not Found（PUBLIC_001）</li>
 *   <li>PS_004: 存在しない組織 → 404 Not Found（PUBLIC_001）</li>
 *   <li>PS_005: timelinePostsPublic 欠落 → 400 Bad Request（バリデーションエラー）</li>
 *   <li>PS_006: publicEventsEnabled 欠落 → 400 Bad Request（バリデーションエラー）</li>
 * </ul>
 */
@WebMvcTest(AdminPublicSettingsController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AdminPublicSettingsController MockMvc テスト (F19.1 Phase 7)")
class AdminPublicSettingsControllerTest {

    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;
    private static final Long OPERATOR_USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminPublicSettingsService adminPublicSettingsService;

    /** @WebMvcTest コンテキスト用: JwtAuthenticationFilter 依存解決 */
    @MockitoBean
    private AuthTokenService authTokenService;

    /** @WebMvcTest コンテキスト用: UserLocaleFilter 依存解決 */
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    /** @WebMvcTest コンテキスト用: ProxyInputContextFilter 依存解決 */
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        // SecurityUtils.getCurrentUserId() は authentication.getName() を Long.valueOf する
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(OPERATOR_USER_ID), null, List.of()));
    }

    // ─────────────────────────────────────────────────────────────────
    // チーム公開設定
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PS_001: PATCH /admin/teams/{teamId}/public-settings → 204")
    void patchTeamPublicSettings_validRequest_returns204() throws Exception {
        willDoNothing().given(adminPublicSettingsService)
                .updateTeamPublicSettings(eq(TEAM_ID), eq(OPERATOR_USER_ID), any());

        mockMvc.perform(patch("/api/v1/admin/teams/{teamId}/public-settings", TEAM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timelinePostsPublic": true,
                                  "publicEventsEnabled": false
                                }
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PS_003: PATCH /admin/teams/{teamId}/public-settings: 存在しないチーム → 404（PUBLIC_001）")
    void patchTeamPublicSettings_teamNotFound_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(adminPublicSettingsService)
                .updateTeamPublicSettings(eq(TEAM_ID), any(), any());

        mockMvc.perform(patch("/api/v1/admin/teams/{teamId}/public-settings", TEAM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timelinePostsPublic": true,
                                  "publicEventsEnabled": true
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PS_005: PATCH /admin/teams/{teamId}/public-settings: timelinePostsPublic 欠落 → 400")
    void patchTeamPublicSettings_missingTimelinePostsPublic_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/teams/{teamId}/public-settings", TEAM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicEventsEnabled": true
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PS_006: PATCH /admin/teams/{teamId}/public-settings: publicEventsEnabled 欠落 → 400")
    void patchTeamPublicSettings_missingPublicEventsEnabled_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/teams/{teamId}/public-settings", TEAM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timelinePostsPublic": true
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────────────
    // 組織公開設定
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PS_002: PATCH /admin/organizations/{orgId}/public-settings → 204")
    void patchOrganizationPublicSettings_validRequest_returns204() throws Exception {
        willDoNothing().given(adminPublicSettingsService)
                .updateOrganizationPublicSettings(eq(ORG_ID), eq(OPERATOR_USER_ID), any());

        mockMvc.perform(patch("/api/v1/admin/organizations/{orgId}/public-settings", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timelinePostsPublic": false,
                                  "publicEventsEnabled": true
                                }
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PS_004: PATCH /admin/organizations/{orgId}/public-settings: 存在しない組織 → 404（PUBLIC_001）")
    void patchOrganizationPublicSettings_orgNotFound_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_001))
                .given(adminPublicSettingsService)
                .updateOrganizationPublicSettings(eq(ORG_ID), any(), any());

        mockMvc.perform(patch("/api/v1/admin/organizations/{orgId}/public-settings", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timelinePostsPublic": true,
                                  "publicEventsEnabled": true
                                }
                                """))
                .andExpect(status().isNotFound());
    }
}
