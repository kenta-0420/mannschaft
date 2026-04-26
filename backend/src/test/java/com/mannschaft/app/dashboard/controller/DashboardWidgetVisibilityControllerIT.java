package com.mannschaft.app.dashboard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.WidgetKey;
import com.mannschaft.app.dashboard.dto.UpdateWidgetVisibilityRequest;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityItemDto;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityResponse;
import com.mannschaft.app.dashboard.service.DashboardWidgetVisibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F02.2.1: {@link DashboardWidgetVisibilityController} の MockMvc 結合テスト。
 *
 * <p>{@code @WebMvcTest} で Web レイヤーのみを起動し、Service 層は {@link MockitoBean} で差し替える。
 * HTTP ⇔ Service の薄いマッピング層（パスバリデーション・パラメータ展開・例外マッピング・
 * リクエストバリデーション）の挙動を検証する。</p>
 *
 * <p>認証戦略: {@code @AutoConfigureMockMvc(addFilters = false)} で Spring Security の
 * フィルタチェインを無効化し、{@link SecurityContextHolder} に直接テスト用の認証情報をセットする。</p>
 */
@WebMvcTest(DashboardWidgetVisibilityController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("DashboardWidgetVisibilityController 結合テスト")
class DashboardWidgetVisibilityControllerIT {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DashboardWidgetVisibilityService visibilityService;

    // JwtAuthenticationFilter の依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // UserLocaleFilter の依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ════════════════════════════════════════════════
    // GET /api/v1/dashboard/team/{teamId}/widget-visibility
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/dashboard/team/{teamId}/widget-visibility")
    class GetTeamWidgetVisibility {

        @Test
        @DisplayName("MEMBER 以上の認証ユーザー → 200 + widgets 配列")
        void 認証MEMBER_200() throws Exception {
            WidgetVisibilityResponse response = WidgetVisibilityResponse.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(TEAM_ID)
                    .widgets(List.of(
                            WidgetVisibilityItemDto.builder()
                                    .widgetKey(WidgetKey.TEAM_NOTICES.name())
                                    .minRole(MinRole.PUBLIC)
                                    .isDefault(true)
                                    .build(),
                            WidgetVisibilityItemDto.builder()
                                    .widgetKey(WidgetKey.TEAM_MEMBER_ATTENDANCE.name())
                                    .minRole(MinRole.MEMBER)
                                    .isDefault(true)
                                    .build()))
                    .build();
            given(visibilityService.getSettings(USER_ID, ScopeType.TEAM, TEAM_ID))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/dashboard/team/{teamId}/widget-visibility", TEAM_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scope_type").value("TEAM"))
                    .andExpect(jsonPath("$.data.scope_id").value(TEAM_ID))
                    .andExpect(jsonPath("$.data.widgets").isArray())
                    .andExpect(jsonPath("$.data.widgets.length()").value(2))
                    .andExpect(jsonPath("$.data.widgets[0].widget_key").value("TEAM_NOTICES"))
                    .andExpect(jsonPath("$.data.widgets[0].min_role").value("PUBLIC"))
                    .andExpect(jsonPath("$.data.widgets[0].is_default").value(true));
        }

        @Test
        @DisplayName("非メンバー → Service が COMMON_002 → 403")
        void 非メンバー_403() throws Exception {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(visibilityService).getSettings(USER_ID, ScopeType.TEAM, TEAM_ID);

            mockMvc.perform(get("/api/v1/dashboard/team/{teamId}/widget-visibility", TEAM_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/dashboard/organization/{orgId}/widget-visibility")
    class GetOrgWidgetVisibility {

        @Test
        @DisplayName("ORGANIZATION スコープで 200 を返す")
        void ORGANIZATION_200() throws Exception {
            WidgetVisibilityResponse response = WidgetVisibilityResponse.builder()
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(ORG_ID)
                    .widgets(List.of(WidgetVisibilityItemDto.builder()
                            .widgetKey(WidgetKey.ORG_NOTICES.name())
                            .minRole(MinRole.PUBLIC)
                            .isDefault(true)
                            .build()))
                    .build();
            given(visibilityService.getSettings(USER_ID, ScopeType.ORGANIZATION, ORG_ID))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/dashboard/organization/{orgId}/widget-visibility", ORG_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scope_type").value("ORGANIZATION"))
                    .andExpect(jsonPath("$.data.scope_id").value(ORG_ID));
        }
    }

    // ════════════════════════════════════════════════
    // PUT /api/v1/dashboard/team/{teamId}/widget-visibility
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("PUT /api/v1/dashboard/team/{teamId}/widget-visibility")
    class PutTeamWidgetVisibility {

        @Test
        @DisplayName("ADMIN による更新 → 200 + 更新後の widgets")
        void ADMIN_更新_200() throws Exception {
            WidgetVisibilityResponse response = WidgetVisibilityResponse.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(TEAM_ID)
                    .widgets(List.of(WidgetVisibilityItemDto.builder()
                            .widgetKey(WidgetKey.TEAM_LATEST_POSTS.name())
                            .minRole(MinRole.PUBLIC)
                            .isDefault(false)
                            .build()))
                    .build();
            given(visibilityService.updateSettings(eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID),
                    any(UpdateWidgetVisibilityRequest.class)))
                    .willReturn(response);

            String body = """
                    {
                      "widgets": [
                        { "widget_key": "TEAM_LATEST_POSTS", "min_role": "PUBLIC" }
                      ]
                    }
                    """;

            mockMvc.perform(put("/api/v1/dashboard/team/{teamId}/widget-visibility", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.widgets[0].widget_key").value("TEAM_LATEST_POSTS"))
                    .andExpect(jsonPath("$.data.widgets[0].min_role").value("PUBLIC"))
                    .andExpect(jsonPath("$.data.widgets[0].is_default").value(false));

            verify(visibilityService).updateSettings(eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID),
                    any(UpdateWidgetVisibilityRequest.class));
        }

        @Test
        @DisplayName("MEMBER で permission なし → Service が COMMON_002 → 403")
        void MEMBER_permissionなし_403() throws Exception {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(visibilityService).updateSettings(
                            eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID),
                            any(UpdateWidgetVisibilityRequest.class));

            String body = """
                    {
                      "widgets": [
                        { "widget_key": "TEAM_LATEST_POSTS", "min_role": "PUBLIC" }
                      ]
                    }
                    """;

            mockMvc.perform(put("/api/v1/dashboard/team/{teamId}/widget-visibility", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("不正な widget_key（小文字） → Bean Validation で 400")
        void 不正widget_key_400() throws Exception {
            // widget_key Pattern (UPPER_SNAKE_CASE) に違反
            String body = """
                    {
                      "widgets": [
                        { "widget_key": "team_latest_posts", "min_role": "PUBLIC" }
                      ]
                    }
                    """;

            mockMvc.perform(put("/api/v1/dashboard/team/{teamId}/widget-visibility", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("widget_key 空 → Bean Validation で 400")
        void widget_key_空_400() throws Exception {
            String body = """
                    {
                      "widgets": [
                        { "widget_key": "", "min_role": "PUBLIC" }
                      ]
                    }
                    """;

            mockMvc.perform(put("/api/v1/dashboard/team/{teamId}/widget-visibility", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("不正な min_role enum → Jackson 変換失敗で 400")
        void 不正minRole_400() throws Exception {
            String body = """
                    {
                      "widgets": [
                        { "widget_key": "TEAM_LATEST_POSTS", "min_role": "INVALID_ROLE" }
                      ]
                    }
                    """;

            mockMvc.perform(put("/api/v1/dashboard/team/{teamId}/widget-visibility", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("widgets 未指定（@NotNull 違反） → 400")
        void widgets未指定_400() throws Exception {
            String body = "{}";

            mockMvc.perform(put("/api/v1/dashboard/team/{teamId}/widget-visibility", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Service 層が COMMON_001 → 400")
        void Service層COMMON_001_400() throws Exception {
            // 未知ウィジェット・ADMIN 限定・スコープ不一致は Service 層で COMMON_001
            willThrow(new BusinessException(CommonErrorCode.COMMON_001))
                    .given(visibilityService).updateSettings(
                            eq(USER_ID), eq(ScopeType.TEAM), eq(TEAM_ID),
                            any(UpdateWidgetVisibilityRequest.class));

            String body = """
                    {
                      "widgets": [
                        { "widget_key": "TEAM_BILLING", "min_role": "PUBLIC" }
                      ]
                    }
                    """;

            mockMvc.perform(put("/api/v1/dashboard/team/{teamId}/widget-visibility", TEAM_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/dashboard/organization/{orgId}/widget-visibility")
    class PutOrgWidgetVisibility {

        @Test
        @DisplayName("ADMIN による組織更新 → 200")
        void ADMIN_組織更新_200() throws Exception {
            WidgetVisibilityResponse response = WidgetVisibilityResponse.builder()
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(ORG_ID)
                    .widgets(List.of(WidgetVisibilityItemDto.builder()
                            .widgetKey(WidgetKey.ORG_NOTICES.name())
                            .minRole(MinRole.MEMBER)
                            .isDefault(false)
                            .build()))
                    .build();
            given(visibilityService.updateSettings(eq(USER_ID), eq(ScopeType.ORGANIZATION), eq(ORG_ID),
                    any(UpdateWidgetVisibilityRequest.class)))
                    .willReturn(response);

            String body = """
                    {
                      "widgets": [
                        { "widget_key": "ORG_NOTICES", "min_role": "MEMBER" }
                      ]
                    }
                    """;

            mockMvc.perform(put("/api/v1/dashboard/organization/{orgId}/widget-visibility", ORG_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scope_type").value("ORGANIZATION"))
                    .andExpect(jsonPath("$.data.widgets[0].widget_key").value("ORG_NOTICES"))
                    .andExpect(jsonPath("$.data.widgets[0].min_role").value("MEMBER"));
        }
    }
}
