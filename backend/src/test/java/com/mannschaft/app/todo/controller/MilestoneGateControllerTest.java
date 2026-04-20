package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.CompletionModeRequest;
import com.mannschaft.app.todo.dto.ForceUnlockRequest;
import com.mannschaft.app.todo.dto.GatesSummaryResponse;
import com.mannschaft.app.todo.dto.MilestoneResponse;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.service.MilestoneGateService;
import com.mannschaft.app.todo.service.ProjectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MilestoneGateController} の単体テスト（F02.7 Phase 15-3）。
 *
 * <p>MockMvc を用いて以下を検証する:
 * <ul>
 *   <li>GET /gates が 200 を返し、サマリー構造が正しいこと</li>
 *   <li>PATCH /completion-mode で AUTO→MANUAL 変更が成功すること</li>
 *   <li>PATCH /force-unlock で reason 未入力時 400 が返ること</li>
 *   <li>MEMBER が /force-unlock を叩くと 403 が返ること</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MilestoneGateController 単体テスト")
class MilestoneGateControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private MilestoneGateService milestoneGateService;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private MilestoneGateController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final Long TEAM_ID = 10L;
    private static final Long PROJECT_ID = 100L;
    private static final Long MILESTONE_ID = 1000L;
    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(messageSource))
                .build();
    }

    /**
     * テスト用の「チームスコープ + 指定プロジェクト ID」プロジェクトを作成する。
     */
    private ProjectEntity teamProject() {
        return ProjectEntity.builder()
                .id(PROJECT_ID)
                .scopeType(TodoScopeType.TEAM)
                .scopeId(TEAM_ID)
                .title("テストプロジェクト")
                .build();
    }

    // ============================================================
    // GET /gates
    // ============================================================

    @Nested
    @DisplayName("GET /gates")
    class GetGates {

        @Test
        @DisplayName("GET_gates_正常系_200でサマリー構造が返る")
        void GET_gates_正常系_200でサマリー構造が返る() throws Exception {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                        .willReturn(Optional.of(teamProject()));
                given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

                GatesSummaryResponse summary = new GatesSummaryResponse(
                        PROJECT_ID,
                        new BigDecimal("62.50"),
                        new BigDecimal("33.33"),
                        3, 1, 1,
                        null,
                        List.of());
                given(projectService.getGatesSummary(PROJECT_ID))
                        .willReturn(ApiResponse.of(summary));

                mockMvc.perform(get("/api/v1/teams/{teamId}/projects/{projectId}/gates", TEAM_ID, PROJECT_ID))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.projectId").value(PROJECT_ID))
                        .andExpect(jsonPath("$.data.overallProgressRate").value(62.50))
                        .andExpect(jsonPath("$.data.totalMilestones").value(3))
                        .andExpect(jsonPath("$.data.lockedMilestones").value(1));
            }
        }
    }

    // ============================================================
    // PATCH /completion-mode
    // ============================================================

    @Nested
    @DisplayName("PATCH /completion-mode")
    class ChangeCompletionMode {

        @Test
        @DisplayName("PATCH_completionMode_AUTO_MANUAL切替_200が返る")
        void PATCH_completionMode_AUTO_MANUAL切替_200が返る() throws Exception {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                        .willReturn(Optional.of(teamProject()));
                given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
                given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

                MilestoneResponse response = new MilestoneResponse(
                        MILESTONE_ID, PROJECT_ID, "テストマイルストーン",
                        null, (short) 0, false, null, null, null,
                        BigDecimal.ZERO, false, null, null, "MANUAL",
                        0L, false, null, null);
                given(projectService.changeMilestoneCompletionMode(
                        eq(PROJECT_ID), eq(MILESTONE_ID), eq("MANUAL")))
                        .willReturn(ApiResponse.of(response));

                CompletionModeRequest request = new CompletionModeRequest("MANUAL");

                mockMvc.perform(patch(
                                "/api/v1/teams/{teamId}/projects/{projectId}/milestones/{milestoneId}/completion-mode",
                                TEAM_ID, PROJECT_ID, MILESTONE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.completionMode").value("MANUAL"));

                verify(projectService).changeMilestoneCompletionMode(PROJECT_ID, MILESTONE_ID, "MANUAL");
            }
        }
    }

    // ============================================================
    // PATCH /force-unlock
    // ============================================================

    @Nested
    @DisplayName("PATCH /force-unlock")
    class ForceUnlock {

        @Test
        @DisplayName("PATCH_forceUnlock_reason未入力_400が返る")
        void PATCH_forceUnlock_reason未入力_400が返る() throws Exception {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                // reason が空文字列 → @NotBlank 違反で 400（サービス到達前に弾かれる）
                String emptyReasonJson = "{\"reason\":\"\"}";

                mockMvc.perform(patch(
                                "/api/v1/teams/{teamId}/projects/{projectId}/milestones/{milestoneId}/force-unlock",
                                TEAM_ID, PROJECT_ID, MILESTONE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(emptyReasonJson))
                        .andExpect(status().isBadRequest());
            }
        }

        @Test
        @DisplayName("PATCH_forceUnlock_MEMBERが実行_403が返る")
        void PATCH_forceUnlock_MEMBERが実行_403が返る() throws Exception {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                        .willReturn(Optional.of(teamProject()));
                given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
                // isAdmin は false（MEMBER）
                given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

                ForceUnlockRequest request = new ForceUnlockRequest("リハーサル先行のため");

                mockMvc.perform(patch(
                                "/api/v1/teams/{teamId}/projects/{projectId}/milestones/{milestoneId}/force-unlock",
                                TEAM_ID, PROJECT_ID, MILESTONE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_002.getCode()));
            }
        }

        @Test
        @DisplayName("PATCH_forceUnlock_ADMINが実行_200が返りサービス呼び出しに理由が渡る")
        void PATCH_forceUnlock_ADMIN実行_成功() throws Exception {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID))
                        .willReturn(Optional.of(teamProject()));
                given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
                given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

                ForceUnlockRequest request = new ForceUnlockRequest("リハーサル先行のため");

                mockMvc.perform(patch(
                                "/api/v1/teams/{teamId}/projects/{projectId}/milestones/{milestoneId}/force-unlock",
                                TEAM_ID, PROJECT_ID, MILESTONE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isOk());

                verify(milestoneGateService).forceUnlock(MILESTONE_ID, USER_ID, "リハーサル先行のため");
            }
        }
    }
}
