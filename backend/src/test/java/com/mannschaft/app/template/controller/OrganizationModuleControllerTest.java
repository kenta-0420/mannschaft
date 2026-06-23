package com.mannschaft.app.template.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.template.dto.OrgModuleResponse;
import com.mannschaft.app.template.dto.ToggleModuleRequest;
import com.mannschaft.app.template.service.ModuleService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link OrganizationModuleController} の @WebMvcTest 結合テスト。
 *
 * <p>受け入れ条件:</p>
 * <ul>
 *   <li>AC-1: GET /api/v1/organizations/{slug}/modules（MEMBER以上）→ 200。slug が resolveOrgId で解決される</li>
 *   <li>AC-2: PATCH /api/v1/organizations/{slug}/modules/{moduleId}/toggle（ADMIN）→ 200。slug 解決</li>
 *   <li>AC-4: 存在しない slug → 404（ORG_001 の NOT_FOUND）</li>
 *   <li>AC-5: 認可不変（非メンバー 403・非 ADMIN の toggle 403 が維持される）</li>
 * </ul>
 */
@WebMvcTest(OrganizationModuleController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("OrganizationModuleController slug 解決テスト")
class OrganizationModuleControllerTest {

    private static final Long USER_ID = 200L;
    private static final Long ORG_ID = 10L;
    private static final Long MODULE_ID = 1L;
    private static final String SLUG = "org-000010";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ModuleService moduleService;

    @MockitoBean
    private AccessControlService accessControlService;

    @MockitoBean
    private OrganizationService organizationService;

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
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    // -------------------------------------------------------
    // AC-1: GET /organizations/{slug}/modules → 200（slug 解決）
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-1: GET /organizations/{slug}/modules - MEMBER以上 → 200・slug が resolveOrgId 経由で解決される")
    void getOrganizationModules_slugResolved_200() throws Exception {
        OrgModuleResponse module = new OrgModuleResponse(MODULE_ID, "カレンダー", "calendar",
                true, LocalDateTime.now());

        given(organizationService.resolveOrgId(SLUG)).willReturn(ORG_ID);
        willDoNothing().given(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
        given(moduleService.getOrganizationModules(ORG_ID)).willReturn(List.of(module));

        mockMvc.perform(get("/api/v1/organizations/{slug}/modules", SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].moduleId").value(MODULE_ID))
                .andExpect(jsonPath("$.data[0].moduleSlug").value("calendar"));
    }

    // -------------------------------------------------------
    // AC-2: PATCH /organizations/{slug}/modules/{moduleId}/toggle → 200（ADMIN）
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-2: PATCH /organizations/{slug}/modules/{moduleId}/toggle - ADMIN → 200・slug 解決")
    void toggleOrganizationModule_admin_slug_200() throws Exception {
        ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

        given(organizationService.resolveOrgId(SLUG)).willReturn(ORG_ID);
        given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        willDoNothing().given(moduleService).toggleOrganizationModule(eq(ORG_ID), any(ToggleModuleRequest.class), eq(USER_ID));

        mockMvc.perform(patch("/api/v1/organizations/{slug}/modules/{moduleId}/toggle", SLUG, MODULE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------
    // AC-4: 存在しない slug → 404
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-4: GET /organizations/{slug}/modules - 存在しない slug → 404")
    void getOrganizationModules_unknownSlug_404() throws Exception {
        given(organizationService.resolveOrgId("unknown-org"))
                .willThrow(new BusinessException(OrgErrorCode.ORG_001));

        mockMvc.perform(get("/api/v1/organizations/{slug}/modules", "unknown-org"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORG_001"));
    }

    // -------------------------------------------------------
    // AC-5: 認可不変（非メンバー 403 / 非 ADMIN の toggle 403）
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-5a: GET /organizations/{slug}/modules - 非メンバー → 403")
    void getOrganizationModules_notMember_403() throws Exception {
        given(organizationService.resolveOrgId(SLUG)).willReturn(ORG_ID);
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");

        mockMvc.perform(get("/api/v1/organizations/{slug}/modules", SLUG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("AC-5b: PATCH /organizations/{slug}/modules/{moduleId}/toggle - 非ADMIN → 403")
    void toggleOrganizationModule_notAdmin_403() throws Exception {
        ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

        given(organizationService.resolveOrgId(SLUG)).willReturn(ORG_ID);
        given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

        mockMvc.perform(patch("/api/v1/organizations/{slug}/modules/{moduleId}/toggle", SLUG, MODULE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }
}
