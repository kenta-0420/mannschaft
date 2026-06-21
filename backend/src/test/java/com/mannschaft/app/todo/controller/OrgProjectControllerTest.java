package com.mannschaft.app.todo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.service.OrganizationService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link OrgProjectController} の単体テスト（組織スコープのプロジェクト API 試練）。
 *
 * <p>組織プロジェクトは team と同等の CRUD。本テストは出陣で
 * {@link ProjectAccessGuard#validateOrgMembership(Long, Long)} /
 * {@link ProjectAccessGuard#validateOrgProjectAccess(Long, Long, Long)} を各 EP に配線し、
 * guard 内部の検証ロジックを実装する前提で記述する（test-first）。</p>
 *
 * <p><b>試練フェーズの red 予定</b>:</p>
 * <ul>
 *   <li>AC-1（非メンバー 403 COMMON_002）: controller が membership guard を呼ばないため red。</li>
 *   <li>AC-3（別組織 IDOR 404 TODO_001）: controller が project guard を呼ばないため red。</li>
 *   <li>AC-5（マイルストーン系も guard を通す）: controller が project guard を呼ばないため red。</li>
 * </ul>
 *
 * <p><b>試練フェーズでも green を保つ（骨格で満たせる）</b>:</p>
 * <ul>
 *   <li>AC-2（組織メンバー正常系・scopeType=ORGANIZATION / scopeId=orgId 配線検証）。</li>
 *   <li>AC-4（visibility=PRIVATE → 400 TODO_004。ProjectService.validateVisibility が投げる前提）。</li>
 * </ul>
 *
 * <p>AC（401 認証必須）は standaloneSetup では検証不能のためスキップする（認証フィルタ層が担保）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrgProjectController 単体テスト（組織プロジェクト IDOR / 認可）")
class OrgProjectControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private TodoService todoService;

    @Mock
    private OrganizationService organizationService;

    @Mock
    private ProjectAccessGuard projectAccessGuard;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private OrgProjectController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final String ORG_SLUG = "org-alpha";
    private static final Long ORG_ID = 200L;
    private static final Long USER_ID = 1L;
    private static final Long PROJECT_ID = 100L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(messageSource))
                .build();
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(ORG_ID);
    }

    private ProjectResponse sampleProject() {
        return new ProjectResponse(
                PROJECT_ID, "組織プロジェクト", "📋", "#FF0000",
                LocalDate.now().plusDays(30), 30L, "ACTIVE",
                BigDecimal.ZERO, 0, 0,
                new ProjectResponse.MilestoneSummary(0L, 0L),
                new ProjectResponse.UserInfo(USER_ID, "テストユーザー"),
                LocalDateTime.now());
    }

    private CreateProjectRequest sampleCreateRequest(String visibility) {
        return new CreateProjectRequest(
                "新規プロジェクト", "説明", "📋", "#FF0000",
                LocalDate.now().plusDays(30), visibility);
    }

    // ============================================================
    // AC-1: 非メンバーが 一覧／作成／{id} 操作 → 403 COMMON_002
    //   accessControlService.checkMembership(userId, orgId, "ORGANIZATION") 由来の
    //   COMMON_002 を guard が投げるスタブ。骨格は guard 未配線のため red。
    // ============================================================

    @Nested
    @DisplayName("AC-1: 非メンバーの組織プロジェクト操作は 403 COMMON_002")
    class NonMemberForbidden {

        @Test
        @DisplayName("一覧_非メンバー_403_COMMON002")
        void 一覧_非メンバー_403() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                        .when(projectAccessGuard).validateOrgMembership(eq(USER_ID), eq(ORG_ID));

                mockMvc.perform(get("/api/v1/organizations/{slug}/projects", ORG_SLUG))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_002.getCode()));
            }
        }

        @Test
        @DisplayName("作成_非メンバー_403_COMMON002")
        void 作成_非メンバー_403() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                        .when(projectAccessGuard).validateOrgMembership(eq(USER_ID), eq(ORG_ID));

                mockMvc.perform(post("/api/v1/organizations/{slug}/projects", ORG_SLUG)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(sampleCreateRequest(null))))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_002.getCode()));
            }
        }

        @Test
        @DisplayName("詳細_非メンバー_403_COMMON002")
        void 詳細_非メンバー_403() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                        .when(projectAccessGuard).validateOrgProjectAccess(eq(USER_ID), eq(ORG_ID), eq(PROJECT_ID));

                mockMvc.perform(get("/api/v1/organizations/{slug}/projects/{id}", ORG_SLUG, PROJECT_ID))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_002.getCode()));
            }
        }
    }

    // ============================================================
    // AC-2: 組織メンバー正常系（一覧 200・作成 201・詳細 200）。
    //   ArgumentCaptor で projectService が scopeType=ORGANIZATION / scopeId=orgId で
    //   呼ばれることを検証。骨格でも guard 非依存ゆえ green を保つ。
    // ============================================================

    @Nested
    @DisplayName("AC-2: 組織メンバーの正常系（scopeType=ORGANIZATION / scopeId=orgId 配線）")
    class MemberHappyPath {

        @Test
        @DisplayName("一覧_メンバー_200_scopeORGANIZATION")
        void 一覧_メンバー_200() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                PagedResponse<ProjectResponse> paged = PagedResponse.of(
                        List.of(sampleProject()),
                        new PagedResponse.PageMeta(1L, 0, 20, 1));
                given(projectService.listProjects(
                        eq(TodoScopeType.ORGANIZATION), eq(ORG_ID), eq(ProjectStatus.ACTIVE), anyInt(), anyInt()))
                        .willReturn(paged);

                mockMvc.perform(get("/api/v1/organizations/{slug}/projects", ORG_SLUG))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data").isArray())
                        .andExpect(jsonPath("$.data[0].id").value(PROJECT_ID));

                ArgumentCaptor<TodoScopeType> scopeTypeCaptor = ArgumentCaptor.forClass(TodoScopeType.class);
                ArgumentCaptor<Long> scopeIdCaptor = ArgumentCaptor.forClass(Long.class);
                verify(projectService).listProjects(
                        scopeTypeCaptor.capture(), scopeIdCaptor.capture(),
                        eq(ProjectStatus.ACTIVE), anyInt(), anyInt());
                assertThat(scopeTypeCaptor.getValue()).isEqualTo(TodoScopeType.ORGANIZATION);
                assertThat(scopeIdCaptor.getValue()).isEqualTo(ORG_ID);
            }
        }

        @Test
        @DisplayName("作成_メンバー_201_scopeORGANIZATION")
        void 作成_メンバー_201() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                given(projectService.createProject(
                        eq(TodoScopeType.ORGANIZATION), eq(ORG_ID), any(CreateProjectRequest.class), eq(USER_ID)))
                        .willReturn(ApiResponse.of(sampleProject()));

                mockMvc.perform(post("/api/v1/organizations/{slug}/projects", ORG_SLUG)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(sampleCreateRequest(null))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.title").value("組織プロジェクト"));

                ArgumentCaptor<TodoScopeType> scopeTypeCaptor = ArgumentCaptor.forClass(TodoScopeType.class);
                ArgumentCaptor<Long> scopeIdCaptor = ArgumentCaptor.forClass(Long.class);
                verify(projectService).createProject(
                        scopeTypeCaptor.capture(), scopeIdCaptor.capture(),
                        any(CreateProjectRequest.class), eq(USER_ID));
                assertThat(scopeTypeCaptor.getValue()).isEqualTo(TodoScopeType.ORGANIZATION);
                assertThat(scopeIdCaptor.getValue()).isEqualTo(ORG_ID);
            }
        }

        @Test
        @DisplayName("詳細_メンバー_200")
        void 詳細_メンバー_200() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                com.mannschaft.app.todo.dto.ProjectDetailResponse detail =
                        org.mockito.Mockito.mock(com.mannschaft.app.todo.dto.ProjectDetailResponse.class);
                given(projectService.getProject(PROJECT_ID)).willReturn(ApiResponse.of(detail));

                mockMvc.perform(get("/api/v1/organizations/{slug}/projects/{id}", ORG_SLUG, PROJECT_ID))
                        .andExpect(status().isOk());
            }
        }
    }

    // ============================================================
    // AC-3: 他組織の projectId（scopeId != resolveOrgId(slug)）→ 404 TODO_001（IDOR）。
    //   guard が TODO_001 を投げるスタブ。骨格は guard 未配線のため red。
    // ============================================================

    @Nested
    @DisplayName("AC-3: 別組織の project への IDOR は 404 TODO_001")
    class CrossOrgProjectIdor {

        @Test
        @DisplayName("詳細取得_別組織のproject_404_TODO001")
        void 詳細取得_別組織のproject_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doThrow(new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND))
                        .when(projectAccessGuard).validateOrgProjectAccess(eq(USER_ID), eq(ORG_ID), eq(PROJECT_ID));

                mockMvc.perform(get("/api/v1/organizations/{slug}/projects/{id}", ORG_SLUG, PROJECT_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }
    }

    // ============================================================
    // AC-4: 作成で visibility=PRIVATE → PRIVATE_ONLY_FOR_PERSONAL（TODO_004 / 400）。
    //   ProjectService.validateVisibility が投げる前提。controller のモック
    //   projectService.createProject を TODO_004 で throw させ status=400 を確認。
    //   骨格でも guard 非依存ゆえ green を保つ。
    // ============================================================

    @Nested
    @DisplayName("AC-4: visibility=PRIVATE は 400 TODO_004（PRIVATE_ONLY_FOR_PERSONAL）")
    class PrivateVisibilityRejected {

        @Test
        @DisplayName("作成_PRIVATE_400_TODO004")
        void 作成_PRIVATE_400() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(projectService.createProject(
                        eq(TodoScopeType.ORGANIZATION), eq(ORG_ID), any(CreateProjectRequest.class), eq(USER_ID)))
                        .willThrow(new BusinessException(TodoErrorCode.PRIVATE_ONLY_FOR_PERSONAL));

                mockMvc.perform(post("/api/v1/organizations/{slug}/projects", ORG_SLUG)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(sampleCreateRequest("PRIVATE"))))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code")
                                .value(TodoErrorCode.PRIVATE_ONLY_FOR_PERSONAL.getCode()));
            }
        }
    }

    // ============================================================
    // AC-5: マイルストーン系 EP も guard を通す（他組織 IDOR で 404 / 非メンバーで 403）。
    //   guard が TODO_001 / COMMON_002 を投げるスタブ。骨格は guard 未配線のため red。
    // ============================================================

    @Nested
    @DisplayName("AC-5: マイルストーン系 EP も認可ゲートを通す")
    class MilestoneGuard {

        @Test
        @DisplayName("マイルストーン一覧_別組織_404_TODO001")
        void マイルストーン一覧_別組織_404() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doThrow(new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND))
                        .when(projectAccessGuard).validateOrgProjectAccess(eq(USER_ID), eq(ORG_ID), eq(PROJECT_ID));

                mockMvc.perform(get("/api/v1/organizations/{slug}/projects/{id}/milestones", ORG_SLUG, PROJECT_ID))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value(TodoErrorCode.PROJECT_NOT_FOUND.getCode()));
            }
        }

        @Test
        @DisplayName("プロジェクト内TODO一覧_非メンバー_403_COMMON002")
        void プロジェクト内TODO一覧_非メンバー_403() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                        .when(projectAccessGuard).validateOrgProjectAccess(eq(USER_ID), eq(ORG_ID), eq(PROJECT_ID));

                mockMvc.perform(get("/api/v1/organizations/{slug}/projects/{id}/todos", ORG_SLUG, PROJECT_ID))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_002.getCode()));
            }
        }
    }
}
