package com.mannschaft.app.todo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link UserProjectController} の単体テスト（F02.3 個人プロジェクト API 試練）。
 *
 * <p>受け入れ条件 AC-2〜AC-9 を MockMvc で検証する。AC-5〜AC-7（IDOR / 他スコープ）は、
 * {@link ProjectAccessGuard#validatePersonalProjectAccess(Long, Long)} が
 * 他人 / 他スコープのプロジェクトに対して TODO_001（404）を投げる前提でアサートする。
 * 試練フェーズでは UserProjectController が guard を呼び出していないため、これらは red になる
 * （出陣で各 EP に guard を配線して green 化する）。</p>
 *
 * <p>AC-1（401 認証必須）は standaloneSetup では実 Security フィルタを通らず検証不能のため、
 * 本テストではスキップする。認証フィルタ層が担保し、検分の実機 E2E で確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserProjectController 単体テスト（個人プロジェクト API）")
class UserProjectControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private TodoService todoService;

    @Mock
    private ProjectAccessGuard projectAccessGuard;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private UserProjectController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long PROJECT_ID = 100L;
    private static final Long MILESTONE_ID = 1000L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(messageSource))
                .build();
    }

    /** テスト用の最小 ProjectResponse を生成する。 */
    private ProjectResponse sampleProject() {
        return new ProjectResponse(
                PROJECT_ID, "個人プロジェクト", "📋", "#FF0000",
                LocalDate.now().plusDays(30), 30L, "ACTIVE",
                BigDecimal.ZERO, 0, 0,
                new ProjectResponse.MilestoneSummary(0L, 0L),
                new ProjectResponse.UserInfo(USER_ID, "テストユーザー"),
                LocalDateTime.now());
    }

    /** IDOR 用: guard が TODO_001（404）を投げるようスタブする。 */
    private void stubGuardThrowsNotFound() {
        doThrow(new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND))
                .when(projectAccessGuard).validatePersonalProjectAccess(eq(USER_ID), eq(PROJECT_ID));
    }

    // ============================================================
    // AC-2: 一覧 GET
    // ============================================================

    @Nested
    @DisplayName("AC-2: GET /api/v1/users/me/projects 一覧")
    class ListProjects {

        @Test
        @DisplayName("一覧取得_自分の個人スコープ_200でdata配列が返る")
        void 一覧取得_自分の個人スコープ_200でdata配列が返る() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                PagedResponse<ProjectResponse> paged = PagedResponse.of(
                        List.of(sampleProject()),
                        new PagedResponse.PageMeta(1L, 0, 20, 1));
                given(projectService.listProjects(
                        eq(TodoScopeType.PERSONAL), eq(USER_ID), eq(ProjectStatus.ACTIVE), anyInt(), anyInt()))
                        .willReturn(paged);

                mockMvc.perform(get("/api/v1/users/me/projects"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data").isArray())
                        .andExpect(jsonPath("$.data[0].id").value(PROJECT_ID))
                        .andExpect(jsonPath("$.data[0].title").value("個人プロジェクト"));

                // PERSONAL スコープ + 現在ユーザー ID で問い合わせていることを検証
                verify(projectService).listProjects(
                        eq(TodoScopeType.PERSONAL), eq(USER_ID), eq(ProjectStatus.ACTIVE), anyInt(), anyInt());
            }
        }
    }

    // ============================================================
    // AC-9: 一覧 既定値
    // ============================================================

    @Nested
    @DisplayName("AC-9: GET 一覧 既定値（status/page/size 未指定）")
    class ListProjectsDefaults {

        @Test
        @DisplayName("一覧取得_クエリ未指定_既定値ACTIVE_page0_size20で200() ")
        void 一覧取得_クエリ未指定_既定値で200() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                PagedResponse<ProjectResponse> paged = PagedResponse.of(
                        List.of(), new PagedResponse.PageMeta(0L, 0, 20, 0));
                given(projectService.listProjects(
                        eq(TodoScopeType.PERSONAL), eq(USER_ID), eq(ProjectStatus.ACTIVE), eq(0), eq(20)))
                        .willReturn(paged);

                mockMvc.perform(get("/api/v1/users/me/projects"))
                        .andExpect(status().isOk());

                // @RequestParam defaultValue が効いて status=ACTIVE, page=0, size=20 で呼ばれること
                verify(projectService).listProjects(
                        eq(TodoScopeType.PERSONAL), eq(USER_ID), eq(ProjectStatus.ACTIVE), eq(0), eq(20));
            }
        }
    }

    // ============================================================
    // AC-3: 作成 POST
    // ============================================================

    @Nested
    @DisplayName("AC-3: POST /api/v1/users/me/projects 作成")
    class CreateProject {

        @Test
        @DisplayName("作成_有効なリクエスト_201でPERSONAL_USER_IDがバインドされる")
        void 作成_有効なリクエスト_201でPERSONALスコープがバインドされる() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                given(projectService.createProject(
                        eq(TodoScopeType.PERSONAL), eq(USER_ID), any(CreateProjectRequest.class), eq(USER_ID)))
                        .willReturn(ApiResponse.of(sampleProject()));

                CreateProjectRequest request = new CreateProjectRequest(
                        "個人プロジェクト", "説明", "📋", "#FF0000",
                        LocalDate.now().plusDays(30), null);

                mockMvc.perform(post("/api/v1/users/me/projects")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.title").value("個人プロジェクト"));

                // scopeType=PERSONAL / scopeId=USER_ID / createdBy=USER_ID を ArgumentCaptor で検証
                ArgumentCaptor<TodoScopeType> scopeTypeCaptor = ArgumentCaptor.forClass(TodoScopeType.class);
                ArgumentCaptor<Long> scopeIdCaptor = ArgumentCaptor.forClass(Long.class);
                ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
                verify(projectService).createProject(
                        scopeTypeCaptor.capture(), scopeIdCaptor.capture(),
                        any(CreateProjectRequest.class), userIdCaptor.capture());

                org.assertj.core.api.Assertions.assertThat(scopeTypeCaptor.getValue())
                        .isEqualTo(TodoScopeType.PERSONAL);
                org.assertj.core.api.Assertions.assertThat(scopeIdCaptor.getValue()).isEqualTo(USER_ID);
                org.assertj.core.api.Assertions.assertThat(userIdCaptor.getValue()).isEqualTo(USER_ID);
            }
        }
    }

    // ============================================================
    // AC-8: 作成バリデーション
    // ============================================================

    @Nested
    @DisplayName("AC-8: POST 作成バリデーション")
    class CreateValidation {

        @Test
        @DisplayName("作成_title空_400が返る")
        void 作成_title空_400が返る() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                // title が空文字列 → @NotBlank 違反でサービス到達前に 400
                String emptyTitleJson = "{\"title\":\"\"}";

                mockMvc.perform(post("/api/v1/users/me/projects")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(emptyTitleJson))
                        .andExpect(status().isBadRequest());
            }
        }
    }

    // ============================================================
    // AC-4: 詳細 GET（自分）
    // ============================================================

    @Nested
    @DisplayName("AC-4: GET /{id} 詳細（自分）")
    class GetOwnProject {

        @Test
        @DisplayName("詳細取得_自分の個人プロジェクト_200が返る")
        void 詳細取得_自分の個人プロジェクト_200が返る() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                // 自分のプロジェクトなので guard は通過（throw しない）
                com.mannschaft.app.todo.dto.ProjectDetailResponse detail =
                        org.mockito.Mockito.mock(com.mannschaft.app.todo.dto.ProjectDetailResponse.class);
                given(projectService.getProject(PROJECT_ID)).willReturn(ApiResponse.of(detail));

                mockMvc.perform(get("/api/v1/users/me/projects/{id}", PROJECT_ID))
                        .andExpect(status().isOk());

                verify(projectService).getProject(PROJECT_ID);
            }
        }
    }

    // ============================================================
    // AC-5: 他人の個人プロジェクトへの IDOR → 404
    // ============================================================

    @Nested
    @DisplayName("AC-5: 他人の個人プロジェクト IDOR は 404 TODO_001")
    class ForeignPersonalProjectIdor {

        @Test
        @DisplayName("詳細取得_他人の個人プロジェクト_404_TODO001")
        void 詳細取得_他人の個人プロジェクト_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubGuardThrowsNotFound();

                mockMvc.perform(get("/api/v1/users/me/projects/{id}", PROJECT_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("更新_他人の個人プロジェクト_404_TODO001")
        void 更新_他人の個人プロジェクト_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubGuardThrowsNotFound();

                String body = "{\"title\":\"乗っ取り\"}";
                mockMvc.perform(put("/api/v1/users/me/projects/{id}", PROJECT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("削除_他人の個人プロジェクト_404_TODO001")
        void 削除_他人の個人プロジェクト_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubGuardThrowsNotFound();

                mockMvc.perform(delete("/api/v1/users/me/projects/{id}", PROJECT_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("完了_他人の個人プロジェクト_404_TODO001")
        void 完了_他人の個人プロジェクト_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubGuardThrowsNotFound();

                mockMvc.perform(patch("/api/v1/users/me/projects/{id}/complete", PROJECT_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("再開_他人の個人プロジェクト_404_TODO001")
        void 再開_他人の個人プロジェクト_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubGuardThrowsNotFound();

                mockMvc.perform(patch("/api/v1/users/me/projects/{id}/reopen", PROJECT_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }
    }

    // ============================================================
    // AC-6: TEAM スコープ project を個人 EP で叩く → 404
    // ============================================================

    @Nested
    @DisplayName("AC-6: TEAM スコープ project を個人 EP で操作は 404 TODO_001")
    class TeamScopedProjectViaPersonalEndpoint {

        @Test
        @DisplayName("詳細取得_TEAMスコープprojectを個人EP_404_TODO001")
        void 詳細取得_TEAMスコープを個人EP_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                // guard が TEAM スコープを検知して TODO_001 を投げる前提
                stubGuardThrowsNotFound();

                mockMvc.perform(get("/api/v1/users/me/projects/{id}", PROJECT_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("更新_TEAMスコープprojectを個人EP_404_TODO001")
        void 更新_TEAMスコープを個人EP_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubGuardThrowsNotFound();

                String body = "{\"title\":\"チーム横取り\"}";
                mockMvc.perform(put("/api/v1/users/me/projects/{id}", PROJECT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("削除_TEAMスコープprojectを個人EP_404_TODO001")
        void 削除_TEAMスコープを個人EP_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubGuardThrowsNotFound();

                mockMvc.perform(delete("/api/v1/users/me/projects/{id}", PROJECT_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }
    }

    // ============================================================
    // AC-7: マイルストーン / todos も他人・他スコープは 404
    // ============================================================

    @Nested
    @DisplayName("AC-7: マイルストーン・todos の他人/他スコープも 404 TODO_001")
    class MilestoneAndTodosIdor {

        @Test
        @DisplayName("マイルストーン一覧_他人project_404_TODO001")
        void マイルストーン一覧_他人project_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubGuardThrowsNotFound();

                mockMvc.perform(get("/api/v1/users/me/projects/{id}/milestones", PROJECT_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("マイルストーン作成_他人project_404_TODO001")
        void マイルストーン作成_他人project_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubGuardThrowsNotFound();

                String body = "{\"title\":\"不正マイルストーン\"}";
                mockMvc.perform(post("/api/v1/users/me/projects/{id}/milestones", PROJECT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("マイルストーン更新_他人project_404_TODO001")
        void マイルストーン更新_他人project_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubGuardThrowsNotFound();

                String body = "{\"title\":\"不正更新\"}";
                mockMvc.perform(put("/api/v1/users/me/projects/{id}/milestones/{mid}", PROJECT_ID, MILESTONE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("マイルストーン削除_他人project_404_TODO001")
        void マイルストーン削除_他人project_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubGuardThrowsNotFound();

                mockMvc.perform(delete("/api/v1/users/me/projects/{id}/milestones/{mid}", PROJECT_ID, MILESTONE_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("マイルストーン完了_他人project_404_TODO001")
        void マイルストーン完了_他人project_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubGuardThrowsNotFound();

                mockMvc.perform(patch("/api/v1/users/me/projects/{id}/milestones/{mid}/complete",
                                PROJECT_ID, MILESTONE_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("プロジェクト内TODO一覧_他人project_404_TODO001")
        void プロジェクト内TODO一覧_他人project_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                stubGuardThrowsNotFound();

                mockMvc.perform(get("/api/v1/users/me/projects/{id}/todos", PROJECT_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }
    }
}
