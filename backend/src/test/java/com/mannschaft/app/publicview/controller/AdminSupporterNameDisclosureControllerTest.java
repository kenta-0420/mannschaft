package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.dto.NameDisclosureChangeLogResponse;
import com.mannschaft.app.publicview.dto.SupporterNameDisclosureResponse;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.publicview.service.SupporterNameDisclosureService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link AdminSupporterNameDisclosureController} の MockMvc 結合テスト (F19.1 Phase 2)。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6 / §7.7</p>
 *
 * <p>検証内容:</p>
 * <ul>
 *   <li>200: PATCH（confirmed=true）→ 正常切替 + changedAt が返る</li>
 *   <li>400: PATCH（confirmed=false）→ PUBLIC_005</li>
 *   <li>404: PATCH（存在しないチーム）→ PUBLIC_004</li>
 *   <li>200: PATCH（同値更新）→ changedAt=null で返る</li>
 *   <li>200: GET /history → 切替履歴一覧</li>
 *   <li>200: PATCH 組織版（confirmed=true）→ 正常切替</li>
 *   <li>200: GET /history 組織版</li>
 *   <li>400: PATCH mode が null → バリデーションエラー</li>
 * </ul>
 */
@WebMvcTest(AdminSupporterNameDisclosureController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AdminSupporterNameDisclosureController 結合テスト (F19.1 Phase 2)")
class AdminSupporterNameDisclosureControllerTest {

    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;
    private static final Long OPERATOR_USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupporterNameDisclosureService service;

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

    /** 各テスト前: ADMIN ロールを持つ認証済みユーザーを設定する。 */
    @BeforeEach
    void setUpSecurityContext() {
        // SecurityUtils.getCurrentUserId() は authentication.getName() を Long.valueOf する
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(OPERATOR_USER_ID), null, List.of()));
    }

    // ─────────────────────────────────────────────────────────────────
    // PATCH チーム版
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /admin/teams/{teamId}/supporter-name-disclosure: confirmed=true → 200 + changedAt が返る")
    void patchTeamDisclosure_confirmed_returns200WithChangedAt() throws Exception {
        LocalDateTime changedAt = LocalDateTime.of(2026, 5, 19, 10, 0, 0);
        given(service.patchTeamDisclosure(eq(TEAM_ID), eq(OPERATOR_USER_ID), any()))
                .willReturn(new SupporterNameDisclosureResponse(NameDisclosureMode.REAL_NAME, changedAt));

        mockMvc.perform(patch("/api/v1/admin/teams/{teamId}/supporter-name-disclosure", TEAM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "REAL_NAME",
                                  "confirmed": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentMode").value("REAL_NAME"))
                .andExpect(jsonPath("$.changedAt").isNotEmpty());
    }

    @Test
    @DisplayName("PATCH /admin/teams/{teamId}/supporter-name-disclosure: confirmed=false → 400 PUBLIC_005")
    void patchTeamDisclosure_notConfirmed_returns400() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.NAME_DISCLOSURE_CONFIRM_REQUIRED))
                .given(service).patchTeamDisclosure(eq(TEAM_ID), any(), any());

        mockMvc.perform(patch("/api/v1/admin/teams/{teamId}/supporter-name-disclosure", TEAM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "REAL_NAME",
                                  "confirmed": false
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /admin/teams/{teamId}/supporter-name-disclosure: 存在しないチーム → 404 PUBLIC_004")
    void patchTeamDisclosure_notFoundTeam_returns404() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.NAME_DISCLOSURE_NOT_FOUND))
                .given(service).patchTeamDisclosure(eq(TEAM_ID), any(), any());

        mockMvc.perform(patch("/api/v1/admin/teams/{teamId}/supporter-name-disclosure", TEAM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "DISPLAY_NAME",
                                  "confirmed": true
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /admin/teams/{teamId}/supporter-name-disclosure: 同値更新 → 200 + changedAt=null")
    void patchTeamDisclosure_sameValue_returns200WithNullChangedAt() throws Exception {
        // 同値更新の場合 Service は changedAt=null で返す
        given(service.patchTeamDisclosure(eq(TEAM_ID), eq(OPERATOR_USER_ID), any()))
                .willReturn(new SupporterNameDisclosureResponse(NameDisclosureMode.DISPLAY_NAME, null));

        mockMvc.perform(patch("/api/v1/admin/teams/{teamId}/supporter-name-disclosure", TEAM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "DISPLAY_NAME",
                                  "confirmed": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentMode").value("DISPLAY_NAME"))
                .andExpect(jsonPath("$.changedAt").isEmpty());
    }

    @Test
    @DisplayName("PATCH /admin/teams/{teamId}/supporter-name-disclosure: mode が null → 400 バリデーション")
    void patchTeamDisclosure_nullMode_returns400() throws Exception {
        // @NotNull バリデーションで Spring MVC が 400 を返す（Service 層に到達しない）
        mockMvc.perform(patch("/api/v1/admin/teams/{teamId}/supporter-name-disclosure", TEAM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "confirmed": true
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────────────
    // GET チーム切替履歴
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/teams/{teamId}/supporter-name-disclosure/history → 200 + 切替履歴リスト")
    void getTeamDisclosureHistory_returns200WithHistoryList() throws Exception {
        UUID logId = UUID.randomUUID();
        LocalDateTime changedAt = LocalDateTime.of(2026, 5, 19, 10, 0, 0);
        given(service.getTeamChangeHistory(eq(TEAM_ID), eq(OPERATOR_USER_ID)))
                .willReturn(List.of(new NameDisclosureChangeLogResponse(
                        logId,
                        NameDisclosureMode.DISPLAY_NAME,
                        NameDisclosureMode.REAL_NAME,
                        true,
                        OPERATOR_USER_ID,
                        changedAt)));

        mockMvc.perform(get("/api/v1/admin/teams/{teamId}/supporter-name-disclosure/history", TEAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].oldMode").value("DISPLAY_NAME"))
                .andExpect(jsonPath("$[0].newMode").value("REAL_NAME"))
                .andExpect(jsonPath("$[0].confirmed").value(true))
                .andExpect(jsonPath("$[0].changedBy").value(OPERATOR_USER_ID));
    }

    @Test
    @DisplayName("GET /admin/teams/{teamId}/supporter-name-disclosure/history: 履歴なし → 200 + 空リスト")
    void getTeamDisclosureHistory_noHistory_returns200EmptyList() throws Exception {
        given(service.getTeamChangeHistory(eq(TEAM_ID), eq(OPERATOR_USER_ID))).willReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/teams/{teamId}/supporter-name-disclosure/history", TEAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ─────────────────────────────────────────────────────────────────
    // PATCH / GET 組織版
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /admin/organizations/{orgId}/supporter-name-disclosure: confirmed=true → 200")
    void patchOrganizationDisclosure_confirmed_returns200() throws Exception {
        LocalDateTime changedAt = LocalDateTime.of(2026, 5, 19, 11, 0, 0);
        given(service.patchOrganizationDisclosure(eq(ORG_ID), eq(OPERATOR_USER_ID), any()))
                .willReturn(new SupporterNameDisclosureResponse(NameDisclosureMode.REAL_NAME, changedAt));

        mockMvc.perform(patch("/api/v1/admin/organizations/{orgId}/supporter-name-disclosure", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mode": "REAL_NAME",
                                  "confirmed": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentMode").value("REAL_NAME"));
    }

    @Test
    @DisplayName("GET /admin/organizations/{orgId}/supporter-name-disclosure/history → 200 + 切替履歴")
    void getOrganizationDisclosureHistory_returns200WithHistoryList() throws Exception {
        UUID logId = UUID.randomUUID();
        LocalDateTime changedAt = LocalDateTime.of(2026, 5, 19, 11, 0, 0);
        given(service.getOrganizationChangeHistory(eq(ORG_ID), eq(OPERATOR_USER_ID)))
                .willReturn(List.of(new NameDisclosureChangeLogResponse(
                        logId,
                        NameDisclosureMode.DISPLAY_NAME,
                        NameDisclosureMode.REAL_NAME,
                        true,
                        OPERATOR_USER_ID,
                        changedAt)));

        mockMvc.perform(get("/api/v1/admin/organizations/{orgId}/supporter-name-disclosure/history", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].oldMode").value("DISPLAY_NAME"))
                .andExpect(jsonPath("$[0].newMode").value("REAL_NAME"));
    }
}
