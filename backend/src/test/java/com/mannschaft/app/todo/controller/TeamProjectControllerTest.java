package com.mannschaft.app.todo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.todo.ProjectStatus;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.CreateProjectRequest;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.security.ProjectAccessGuard;
import com.mannschaft.app.todo.service.ProjectService;
import com.mannschaft.app.todo.service.TodoService;
import com.mannschaft.app.team.service.TeamService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TeamProjectController} の単体テスト（チーム版 IDOR / 認可ゲート試練）。
 *
 * <p>現状 TeamProjectController は {@code /{id}} 系 EP でスコープ整合性・メンバーシップを
 * <b>検証していない</b>（IDOR 脆弱）。本テストは出陣で
 * {@link ProjectAccessGuard#validateTeamProjectAccess(Long, Long, Long)} /
 * {@link ProjectAccessGuard#validateTeamMembership(Long, Long)} を各 EP に配線する前提で記述する。
 * 試練フェーズでは guard が呼ばれないため AC-10〜AC-12 は red になり、
 * 既存挙動（200/201）の AC-13 のみ green を保つ。</p>
 *
 * <p>AC-1（401 認証必須）は standaloneSetup では検証不能のため本テストではスキップする
 * （認証フィルタ層が担保・検分の実機 E2E で確認）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TeamProjectController 単体テスト（チーム版 IDOR / 認可）")
class TeamProjectControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private TodoService todoService;

    @Mock
    private TeamService teamService;

    @Mock
    private ProjectAccessGuard projectAccessGuard;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private TeamProjectController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final String TEAM_SLUG = "team-alpha";
    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final Long PROJECT_ID = 100L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(messageSource))
                .build();
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
    }

    private ProjectResponse sampleProject() {
        return new ProjectResponse(
                PROJECT_ID, "チームプロジェクト", "📋", "#FF0000",
                LocalDate.now().plusDays(30), 30L, "ACTIVE",
                BigDecimal.ZERO, 0, 0,
                new ProjectResponse.MilestoneSummary(0L, 0L),
                new ProjectResponse.UserInfo(USER_ID, "テストユーザー"),
                LocalDateTime.now());
    }

    /** /{id} 系 IDOR 用: guard が TODO_001（404）を投げるようスタブ。 */
    private void stubProjectGuardThrowsNotFound() {
        doThrow(new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND))
                .when(projectAccessGuard).validateTeamProjectAccess(eq(USER_ID), eq(TEAM_ID), eq(PROJECT_ID));
    }

    /** 非メンバー用: guard が COMMON_002（403）を投げるようスタブ。 */
    private void stubProjectGuardThrowsForbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(projectAccessGuard).validateTeamProjectAccess(eq(USER_ID), eq(TEAM_ID), eq(PROJECT_ID));
    }

    /** 非メンバー（一覧/作成）用: membership guard が COMMON_002（403）を投げるようスタブ。 */
    private void stubMembershipGuardThrowsForbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(projectAccessGuard).validateTeamMembership(eq(USER_ID), eq(TEAM_ID));
    }

    // ============================================================
    // AC-10: 別チームの project（scopeId 不一致）IDOR は 404 TODO_001
    // ============================================================

    @Nested
    @DisplayName("AC-10: 別チームの project への IDOR は 404 TODO_001")
    class CrossTeamProjectIdor {

        @Test
        @DisplayName("詳細取得_別チームのproject_404_TODO001")
        void 詳細取得_別チームのproject_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubProjectGuardThrowsNotFound();

                mockMvc.perform(get("/api/v1/teams/{teamId}/projects/{id}", TEAM_SLUG, PROJECT_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("更新_別チームのproject_404_TODO001")
        void 更新_別チームのproject_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubProjectGuardThrowsNotFound();

                String body = "{\"title\":\"他チーム横取り\"}";
                mockMvc.perform(put("/api/v1/teams/{teamId}/projects/{id}", TEAM_SLUG, PROJECT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("削除_別チームのproject_404_TODO001")
        void 削除_別チームのproject_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubProjectGuardThrowsNotFound();

                mockMvc.perform(delete("/api/v1/teams/{teamId}/projects/{id}", TEAM_SLUG, PROJECT_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }
    }

    // ============================================================
    // AC-11: 非メンバーが /{id} を操作 → 403 COMMON_002
    // ============================================================

    @Nested
    @DisplayName("AC-11: 非メンバーの project 操作は 403 COMMON_002")
    class NonMemberProjectOperation {

        @Test
        @DisplayName("詳細取得_非メンバー_403_COMMON002")
        void 詳細取得_非メンバー_403() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubProjectGuardThrowsForbidden();

                mockMvc.perform(get("/api/v1/teams/{teamId}/projects/{id}", TEAM_SLUG, PROJECT_ID))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_002.getCode()));
            }
        }

        @Test
        @DisplayName("更新_非メンバー_403_COMMON002")
        void 更新_非メンバー_403() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubProjectGuardThrowsForbidden();

                String body = "{\"title\":\"部外者更新\"}";
                mockMvc.perform(put("/api/v1/teams/{teamId}/projects/{id}", TEAM_SLUG, PROJECT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_002.getCode()));
            }
        }

        @Test
        @DisplayName("削除_非メンバー_403_COMMON002")
        void 削除_非メンバー_403() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubProjectGuardThrowsForbidden();

                mockMvc.perform(delete("/api/v1/teams/{teamId}/projects/{id}", TEAM_SLUG, PROJECT_ID))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_002.getCode()));
            }
        }
    }

    // ============================================================
    // AC-12: 一覧/作成も非メンバー → 403 COMMON_002
    // ============================================================

    @Nested
    @DisplayName("AC-12: 一覧／作成も非メンバーは 403 COMMON_002")
    class NonMemberListOrCreate {

        @Test
        @DisplayName("一覧_非メンバー_403_COMMON002")
        void 一覧_非メンバー_403() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubMembershipGuardThrowsForbidden();

                mockMvc.perform(get("/api/v1/teams/{teamId}/projects", TEAM_SLUG))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_002.getCode()));
            }
        }

        @Test
        @DisplayName("作成_非メンバー_403_COMMON002")
        void 作成_非メンバー_403() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubMembershipGuardThrowsForbidden();

                CreateProjectRequest request = new CreateProjectRequest(
                        "新規プロジェクト", "説明", "📋", "#FF0000",
                        LocalDate.now().plusDays(30), null);

                mockMvc.perform(post("/api/v1/teams/{teamId}/projects", TEAM_SLUG)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_002.getCode()));
            }
        }
    }

    // ============================================================
    // AC-13: 正規メンバーの正常系（一覧 200・詳細 200・作成 201）
    // ============================================================

    @Nested
    @DisplayName("AC-13: 正規メンバーの正常系")
    class MemberHappyPath {

        @Test
        @DisplayName("一覧_正規メンバー_200")
        void 一覧_正規メンバー_200() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                // guard は通過（throw しない）

                PagedResponse<ProjectResponse> paged = PagedResponse.of(
                        List.of(sampleProject()),
                        new PagedResponse.PageMeta(1L, 0, 20, 1));
                given(projectService.listProjects(
                        eq(TodoScopeType.TEAM), eq(TEAM_ID), eq(ProjectStatus.ACTIVE), anyInt(), anyInt()))
                        .willReturn(paged);

                mockMvc.perform(get("/api/v1/teams/{teamId}/projects", TEAM_SLUG))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data").isArray())
                        .andExpect(jsonPath("$.data[0].id").value(PROJECT_ID));
            }
        }

        @Test
        @DisplayName("詳細_正規メンバー_200")
        void 詳細_正規メンバー_200() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                com.mannschaft.app.todo.dto.ProjectDetailResponse detail =
                        org.mockito.Mockito.mock(com.mannschaft.app.todo.dto.ProjectDetailResponse.class);
                given(projectService.getProject(PROJECT_ID)).willReturn(ApiResponse.of(detail));

                mockMvc.perform(get("/api/v1/teams/{teamId}/projects/{id}", TEAM_SLUG, PROJECT_ID))
                        .andExpect(status().isOk());
            }
        }

        @Test
        @DisplayName("作成_正規メンバー_201")
        void 作成_正規メンバー_201() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                given(projectService.createProject(
                        eq(TodoScopeType.TEAM), eq(TEAM_ID), any(CreateProjectRequest.class), eq(USER_ID)))
                        .willReturn(ApiResponse.of(sampleProject()));

                CreateProjectRequest request = new CreateProjectRequest(
                        "新規プロジェクト", "説明", "📋", "#FF0000",
                        LocalDate.now().plusDays(30), null);

                mockMvc.perform(post("/api/v1/teams/{teamId}/projects", TEAM_SLUG)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.title").value("チームプロジェクト"));
            }
        }
    }
}
