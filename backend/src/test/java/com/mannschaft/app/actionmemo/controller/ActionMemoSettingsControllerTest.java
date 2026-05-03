package com.mannschaft.app.actionmemo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.dto.ActionMemoSettingsResponse;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoSettingsRequest;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.actionmemo.service.ActionMemoSettingsService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ActionMemoSettingsController} の MockMvc 結合テスト（F02.5 Phase 3）。
 *
 * <p>Phase 3 で追加された設定フィールドの取得・更新を検証する:</p>
 * <ul>
 *   <li>{@code GET /api/v1/action-memo-settings} レスポンスに
 *       {@code default_post_team_id} と {@code default_category} が含まれる</li>
 *   <li>{@code PATCH /api/v1/action-memo-settings} で両フィールドが更新される</li>
 *   <li>非所属チームを {@code default_post_team_id} に指定すると 400
 *       ({@code ACTION_MEMO_020 invalid_default_team})</li>
 * </ul>
 */
@WebMvcTest(ActionMemoSettingsController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ActionMemoSettingsController 結合テスト")
class ActionMemoSettingsControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ActionMemoSettingsService settingsService;

    // JwtAuthenticationFilter 依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // F11.3: UserLocaleFilter 依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで必要）
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ════════════════════════════════════════════════════════════════════
    // GET /api/v1/action-memo-settings
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/action-memo-settings")
    class GetSettings {

        @Test
        @DisplayName("Phase 3: レスポンスに default_post_team_id / default_category を含む")
        void getSettings_Phase3フィールドあり_200() throws Exception {
            ActionMemoSettingsResponse stub = ActionMemoSettingsResponse.builder()
                    .moodEnabled(true)
                    .defaultPostTeamId(TEAM_ID)
                    .defaultCategory(ActionMemoCategory.WORK)
                    .build();
            given(settingsService.getSettings(USER_ID)).willReturn(stub);

            mockMvc.perform(get("/api/v1/action-memo-settings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.mood_enabled").value(true))
                    .andExpect(jsonPath("$.data.default_post_team_id").value(TEAM_ID))
                    .andExpect(jsonPath("$.data.default_category").value("WORK"));
        }

        @Test
        @DisplayName("レコード未作成: default_post_team_id=null / default_category=PRIVATE")
        void getSettings_レコード未作成_デフォルト値() throws Exception {
            ActionMemoSettingsResponse stub = ActionMemoSettingsResponse.builder()
                    .moodEnabled(false)
                    .defaultPostTeamId(null)
                    .defaultCategory(ActionMemoCategory.PRIVATE)
                    .build();
            given(settingsService.getSettings(USER_ID)).willReturn(stub);

            mockMvc.perform(get("/api/v1/action-memo-settings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.mood_enabled").value(false))
                    .andExpect(jsonPath("$.data.default_post_team_id").doesNotExist())
                    .andExpect(jsonPath("$.data.default_category").value("PRIVATE"));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // PATCH /api/v1/action-memo-settings
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PATCH /api/v1/action-memo-settings")
    class UpdateSettings {

        @Test
        @DisplayName("Phase 3: default_post_team_id と default_category を同時更新")
        void updateSettings_Phase3両フィールド更新_200() throws Exception {
            ActionMemoSettingsResponse stub = ActionMemoSettingsResponse.builder()
                    .moodEnabled(false)
                    .defaultPostTeamId(TEAM_ID)
                    .defaultCategory(ActionMemoCategory.WORK)
                    .build();
            given(settingsService.updateSettings(eq(USER_ID), any(UpdateActionMemoSettingsRequest.class)))
                    .willReturn(stub);

            String body = """
                    {
                      "default_post_team_id": %d,
                      "default_category": "WORK"
                    }
                    """.formatted(TEAM_ID);

            mockMvc.perform(patch("/api/v1/action-memo-settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.default_post_team_id").value(TEAM_ID))
                    .andExpect(jsonPath("$.data.default_category").value("WORK"));
        }

        @Test
        @DisplayName("Phase 3: default_category のみ更新（部分更新）")
        void updateSettings_default_categoryのみ_200() throws Exception {
            ActionMemoSettingsResponse stub = ActionMemoSettingsResponse.builder()
                    .moodEnabled(false)
                    .defaultPostTeamId(null)
                    .defaultCategory(ActionMemoCategory.OTHER)
                    .build();
            given(settingsService.updateSettings(eq(USER_ID), any(UpdateActionMemoSettingsRequest.class)))
                    .willReturn(stub);

            String body = """
                    { "default_category": "OTHER" }
                    """;

            mockMvc.perform(patch("/api/v1/action-memo-settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.default_category").value("OTHER"));
        }

        @Test
        @DisplayName("非所属チームを default_post_team_id に指定: 400 ACTION_MEMO_020")
        void updateSettings_非所属チーム_400() throws Exception {
            willThrow(new BusinessException(ActionMemoErrorCode.ACTION_MEMO_INVALID_DEFAULT_TEAM))
                    .given(settingsService)
                    .updateSettings(eq(USER_ID), any(UpdateActionMemoSettingsRequest.class));

            String body = """
                    { "default_post_team_id": 9999 }
                    """;

            mockMvc.perform(patch("/api/v1/action-memo-settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_020"));
        }
    }
}
