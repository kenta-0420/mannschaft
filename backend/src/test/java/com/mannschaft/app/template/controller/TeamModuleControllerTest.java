package com.mannschaft.app.template.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.template.dto.TeamModuleResponse;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TeamModuleController} の @WebMvcTest 結合テスト。
 *
 * <p>受け入れ条件:</p>
 * <ul>
 *   <li>AC-3: GET/toggle/template エンドポイントが slug 解決で 200</li>
 *   <li>AC-4: 存在しない slug → 404（TEAM_001 の NOT_FOUND）</li>
 * </ul>
 */
@WebMvcTest(TeamModuleController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TeamModuleController slug 解決テスト")
class TeamModuleControllerTest {

    private static final Long USER_ID = 300L;
    private static final Long TEAM_ID = 20L;
    private static final Long MODULE_ID = 2L;
    private static final Long TEMPLATE_ID = 5L;
    private static final String SLUG = "team-000020";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ModuleService moduleService;

    @MockitoBean
    private TeamService teamService;

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
    // AC-3: GET /teams/{slug}/modules → 200（slug 解決）
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-3a: GET /teams/{slug}/modules - slug 解決 → 200")
    void getTeamModules_slugResolved_200() throws Exception {
        TeamModuleResponse module = new TeamModuleResponse(MODULE_ID, "シフト", "shift",
                true, LocalDateTime.now(), null);

        given(teamService.resolveTeamId(SLUG)).willReturn(TEAM_ID);
        given(moduleService.getTeamModules(TEAM_ID)).willReturn(List.of(module));

        mockMvc.perform(get("/api/v1/teams/{slug}/modules", SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].moduleId").value(MODULE_ID))
                .andExpect(jsonPath("$.data[0].moduleSlug").value("shift"));
    }

    // -------------------------------------------------------
    // AC-3b: PATCH /teams/{slug}/modules/{moduleId}/toggle → 200
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-3b: PATCH /teams/{slug}/modules/{moduleId}/toggle - slug 解決 → 200")
    void toggleTeamModule_slugResolved_200() throws Exception {
        ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, false);

        given(teamService.resolveTeamId(SLUG)).willReturn(TEAM_ID);
        willDoNothing().given(moduleService).toggleTeamModule(eq(TEAM_ID), any(ToggleModuleRequest.class), eq(USER_ID));

        mockMvc.perform(patch("/api/v1/teams/{slug}/modules/{moduleId}/toggle", SLUG, MODULE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------
    // AC-3c: PUT /teams/{slug}/modules/template → 200
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-3c: PUT /teams/{slug}/modules/template - slug 解決 → 200")
    void applyTemplate_slugResolved_200() throws Exception {
        given(teamService.resolveTeamId(SLUG)).willReturn(TEAM_ID);
        willDoNothing().given(moduleService).applyTemplate(eq(TEAM_ID), eq(TEMPLATE_ID), eq(USER_ID));

        mockMvc.perform(put("/api/v1/teams/{slug}/modules/template", SLUG)
                        .param("templateId", TEMPLATE_ID.toString()))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------
    // AC-4: 存在しない slug → 404
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-4: GET /teams/{slug}/modules - 存在しない slug → 404")
    void getTeamModules_unknownSlug_404() throws Exception {
        given(teamService.resolveTeamId("unknown-team"))
                .willThrow(new BusinessException(TeamErrorCode.TEAM_001));

        mockMvc.perform(get("/api/v1/teams/{slug}/modules", "unknown-team"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TEAM_001"));
    }
}
