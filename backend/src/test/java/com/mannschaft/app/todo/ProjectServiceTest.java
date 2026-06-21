package com.mannschaft.app.todo;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.todo.dto.CreateMilestoneRequest;
import com.mannschaft.app.todo.dto.CreateProjectRequest;
import com.mannschaft.app.todo.dto.MilestoneResponse;
import com.mannschaft.app.todo.dto.ProjectDetailResponse;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.dto.UpdateMilestoneRequest;
import com.mannschaft.app.todo.dto.UpdateProjectRequest;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.entity.ProjectMilestoneEntity;
import com.mannschaft.app.todo.repository.ProjectMilestoneRepository;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import com.mannschaft.app.todo.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * {@link ProjectService} の単体テスト。
 * プロジェクトCRUD・マイルストーン管理・進捗計算を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectService 単体テスト")
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMilestoneRepository milestoneRepository;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private com.mannschaft.app.todo.service.MilestoneGateService milestoneGateService;

    @Mock
    private com.mannschaft.app.auth.service.AuditLogService auditLogService;

    @Mock
    private com.mannschaft.app.membership.repository.MembershipRepository membershipRepository;

    @Mock
    private com.mannschaft.app.team.service.TeamService teamService;

    @InjectMocks
    private ProjectService projectService;

    @BeforeEach
    void setUpNameResolver() {
        lenient().when(nameResolverService.resolveUserDisplayNames(any()))
                .thenReturn(Map.of(USER_ID, "テストユーザー"));
    }

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long PROJECT_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long MILESTONE_ID = 50L;
    private static final TodoScopeType SCOPE_TYPE = TodoScopeType.TEAM;

    private ProjectEntity createActiveProject() {
        return ProjectEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .title("テストプロジェクト")
                .description("テスト説明")
                .emoji("📋")
                .color("#FF0000")
                .dueDate(LocalDate.now().plusDays(30))
                .status(ProjectStatus.ACTIVE)
                .progressRate(BigDecimal.ZERO)
                .totalTodos((short) 0)
                .completedTodos((short) 0)
                .visibility(ProjectVisibility.MEMBERS_ONLY)
                .createdBy(USER_ID)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private ProjectEntity createCompletedProject() {
        return ProjectEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .title("完了プロジェクト")
                .status(ProjectStatus.COMPLETED)
                .progressRate(BigDecimal.valueOf(100))
                .totalTodos((short) 5)
                .completedTodos((short) 5)
                .visibility(ProjectVisibility.MEMBERS_ONLY)
                .createdBy(USER_ID)
                .completedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private ProjectMilestoneEntity createMilestone() {
        return ProjectMilestoneEntity.builder()
                .projectId(PROJECT_ID)
                .title("マイルストーン1")
                .dueDate(LocalDate.now().plusDays(15))
                .sortOrder((short) 1)
                .isCompleted(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ========================================
    // listProjects
    // ========================================

    @Nested
    @DisplayName("listProjects")
    class ListProjects {

        @Test
        @DisplayName("正常系: プロジェクト一覧が返却される")
        void listProjects_正常_一覧返却() {
            // Given
            ProjectEntity project = createActiveProject();
            Page<ProjectEntity> page = new PageImpl<>(List.of(project));
            given(projectRepository.findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
                    eq(SCOPE_TYPE), eq(SCOPE_ID), eq(ProjectStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(page);
            given(milestoneRepository.countByProjectId(any())).willReturn(2L);
            given(milestoneRepository.countByProjectIdAndIsCompletedTrue(any())).willReturn(1L);

            // When
            PagedResponse<ProjectResponse> response = projectService.listProjects(
                    SCOPE_TYPE, SCOPE_ID, ProjectStatus.ACTIVE, 0, 20);

            // Then
            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getTitle()).isEqualTo("テストプロジェクト");
            assertThat(response.getMeta().getPage()).isEqualTo(0);
        }
    }

    // ========================================
    // getProject
    // ========================================

    @Nested
    @DisplayName("getProject")
    class GetProject {

        @Test
        @DisplayName("正常系: プロジェクト詳細が返却される")
        void getProject_正常_詳細返却() {
            // Given
            ProjectEntity project = createActiveProject();
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(milestoneRepository.findByProjectIdOrderBySortOrderAsc(PROJECT_ID)).willReturn(List.of());
            given(todoRepository.countByProjectIdAndMilestoneIdIsNullAndDeletedAtIsNull(PROJECT_ID)).willReturn(3L);
            given(todoRepository.countByProjectIdAndMilestoneIdIsNullAndStatusAndDeletedAtIsNull(
                    PROJECT_ID, TodoStatus.COMPLETED)).willReturn(1L);

            // When
            ApiResponse<ProjectDetailResponse> response = projectService.getProject(PROJECT_ID);

            // Then
            assertThat(response.getData().getTitle()).isEqualTo("テストプロジェクト");
            assertThat(response.getData().getUnassignedTodos().getTotal()).isEqualTo(3);
            assertThat(response.getData().getUnassignedTodos().getCompleted()).isEqualTo(1);
        }

        @Test
        @DisplayName("異常系: プロジェクト不在でTODO_001例外")
        void getProject_不在_TODO001例外() {
            // Given
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> projectService.getProject(PROJECT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_001"));
        }
    }

    // ========================================
    // createProject
    // ========================================

    @Nested
    @DisplayName("createProject")
    class CreateProject {

        @Test
        @DisplayName("正常系: プロジェクトが作成される")
        void createProject_正常_作成成功() {
            // Given
            CreateProjectRequest request = new CreateProjectRequest(
                    "新規プロジェクト", "説明", "🎯", "#00FF00",
                    LocalDate.now().plusDays(60), null);
            given(projectRepository.countByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
                    SCOPE_TYPE, SCOPE_ID, ProjectStatus.ACTIVE)).willReturn(5L);
            given(projectRepository.existsByScopeTypeAndScopeIdAndTitleAndDeletedAtIsNull(
                    SCOPE_TYPE, SCOPE_ID, "新規プロジェクト")).willReturn(false);
            given(projectRepository.save(any(ProjectEntity.class)))
                    .willAnswer(invocation -> {
                        ProjectEntity e = invocation.getArgument(0);
                        // Simulate @PrePersist since JPA callbacks don't fire in unit tests
                        java.lang.reflect.Method m = ProjectEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });
            given(milestoneRepository.countByProjectId(any())).willReturn(0L);
            given(milestoneRepository.countByProjectIdAndIsCompletedTrue(any())).willReturn(0L);

            // When
            ApiResponse<ProjectResponse> response = projectService.createProject(
                    SCOPE_TYPE, SCOPE_ID, request, USER_ID);

            // Then
            assertThat(response.getData().getTitle()).isEqualTo("新規プロジェクト");
            verify(projectRepository).save(any(ProjectEntity.class));
        }

        @Test
        @DisplayName("異常系: ACTIVEプロジェクト上限超過でTODO_003例外")
        void createProject_上限超過_TODO003例外() {
            // Given
            CreateProjectRequest request = new CreateProjectRequest(
                    "新規プロジェクト", null, null, null, null, null);
            given(projectRepository.countByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
                    SCOPE_TYPE, SCOPE_ID, ProjectStatus.ACTIVE)).willReturn(20L);

            // When / Then
            assertThatThrownBy(() -> projectService.createProject(SCOPE_TYPE, SCOPE_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_003"));
        }

        @Test
        @DisplayName("異常系: 同名プロジェクト重複でTODO_002例外")
        void createProject_タイトル重複_TODO002例外() {
            // Given
            CreateProjectRequest request = new CreateProjectRequest(
                    "既存プロジェクト", null, null, null, null, null);
            given(projectRepository.countByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
                    SCOPE_TYPE, SCOPE_ID, ProjectStatus.ACTIVE)).willReturn(5L);
            given(projectRepository.existsByScopeTypeAndScopeIdAndTitleAndDeletedAtIsNull(
                    SCOPE_TYPE, SCOPE_ID, "既存プロジェクト")).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> projectService.createProject(SCOPE_TYPE, SCOPE_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_002"));
        }

        @Test
        @DisplayName("異常系: PRIVATEをTEAMスコープに設定でTODO_004例外")
        void createProject_PRIVATEスコープ違反_TODO004例外() {
            // Given
            CreateProjectRequest request = new CreateProjectRequest(
                    "秘密プロジェクト", null, null, null, null, "PRIVATE");
            given(projectRepository.countByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
                    SCOPE_TYPE, SCOPE_ID, ProjectStatus.ACTIVE)).willReturn(5L);
            given(projectRepository.existsByScopeTypeAndScopeIdAndTitleAndDeletedAtIsNull(
                    SCOPE_TYPE, SCOPE_ID, "秘密プロジェクト")).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> projectService.createProject(SCOPE_TYPE, SCOPE_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_004"));
        }

        @Test
        @DisplayName("正常系: PRIVATEをPERSONALスコープに設定可能")
        void createProject_PRIVATEパーソナル_正常() {
            // Given
            CreateProjectRequest request = new CreateProjectRequest(
                    "個人プロジェクト", null, null, null, null, "PRIVATE");
            given(projectRepository.countByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
                    TodoScopeType.PERSONAL, SCOPE_ID, ProjectStatus.ACTIVE)).willReturn(0L);
            given(projectRepository.existsByScopeTypeAndScopeIdAndTitleAndDeletedAtIsNull(
                    TodoScopeType.PERSONAL, SCOPE_ID, "個人プロジェクト")).willReturn(false);
            given(projectRepository.save(any(ProjectEntity.class)))
                    .willAnswer(invocation -> {
                        ProjectEntity e = invocation.getArgument(0);
                        // Simulate @PrePersist since JPA callbacks don't fire in unit tests
                        java.lang.reflect.Method m = ProjectEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });
            given(milestoneRepository.countByProjectId(any())).willReturn(0L);
            given(milestoneRepository.countByProjectIdAndIsCompletedTrue(any())).willReturn(0L);

            // When
            ApiResponse<ProjectResponse> response = projectService.createProject(
                    TodoScopeType.PERSONAL, SCOPE_ID, request, USER_ID);

            // Then
            assertThat(response.getData().getTitle()).isEqualTo("個人プロジェクト");
        }
    }

    // ========================================
    // updateProject
    // ========================================

    @Nested
    @DisplayName("updateProject")
    class UpdateProject {

        @Test
        @DisplayName("正常系: プロジェクトが更新される")
        void updateProject_正常_更新成功() {
            // Given
            ProjectEntity project = createActiveProject();
            UpdateProjectRequest request = new UpdateProjectRequest(
                    "更新タイトル", "更新説明", "🚀", "#0000FF",
                    LocalDate.now().plusDays(90), null, null);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(projectRepository.existsByScopeTypeAndScopeIdAndTitleAndDeletedAtIsNull(
                    SCOPE_TYPE, SCOPE_ID, "更新タイトル")).willReturn(false);
            given(projectRepository.save(any(ProjectEntity.class)))
                    .willAnswer(invocation -> {
                        ProjectEntity e = invocation.getArgument(0);
                        // Simulate @PrePersist since JPA callbacks don't fire in unit tests
                        java.lang.reflect.Method m = ProjectEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });
            given(milestoneRepository.countByProjectId(any())).willReturn(0L);
            given(milestoneRepository.countByProjectIdAndIsCompletedTrue(any())).willReturn(0L);

            // When
            ApiResponse<ProjectResponse> response = projectService.updateProject(PROJECT_ID, request);

            // Then
            assertThat(response.getData().getTitle()).isEqualTo("更新タイトル");
            verify(projectRepository).save(any(ProjectEntity.class));
        }

        @Test
        @DisplayName("異常系: タイトル変更時に重複でTODO_002例外")
        void updateProject_タイトル重複_TODO002例外() {
            // Given
            ProjectEntity project = createActiveProject();
            UpdateProjectRequest request = new UpdateProjectRequest(
                    "既存タイトル", null, null, null, null, null, null);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(projectRepository.existsByScopeTypeAndScopeIdAndTitleAndDeletedAtIsNull(
                    SCOPE_TYPE, SCOPE_ID, "既存タイトル")).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> projectService.updateProject(PROJECT_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_002"));
        }

        @Test
        @DisplayName("正常系: 同一タイトルでの更新は重複チェックをスキップ")
        void updateProject_同一タイトル_スキップ() {
            // Given
            ProjectEntity project = createActiveProject();
            UpdateProjectRequest request = new UpdateProjectRequest(
                    "テストプロジェクト", "更新説明", null, null, null, null, null);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(projectRepository.save(any(ProjectEntity.class)))
                    .willAnswer(invocation -> {
                        ProjectEntity e = invocation.getArgument(0);
                        // Simulate @PrePersist since JPA callbacks don't fire in unit tests
                        java.lang.reflect.Method m = ProjectEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });
            given(milestoneRepository.countByProjectId(any())).willReturn(0L);
            given(milestoneRepository.countByProjectIdAndIsCompletedTrue(any())).willReturn(0L);

            // When
            ApiResponse<ProjectResponse> response = projectService.updateProject(PROJECT_ID, request);

            // Then
            assertThat(response.getData().getTitle()).isEqualTo("テストプロジェクト");
        }
    }

    // ========================================
    // deleteProject
    // ========================================

    @Nested
    @DisplayName("deleteProject")
    class DeleteProject {

        @Test
        @DisplayName("正常系: プロジェクトが論理削除される")
        void deleteProject_正常_論理削除() {
            // Given
            ProjectEntity project = createActiveProject();
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));

            // When
            projectService.deleteProject(PROJECT_ID);

            // Then
            assertThat(project.getDeletedAt()).isNotNull();
            verify(projectRepository).save(project);
        }

        @Test
        @DisplayName("異常系: プロジェクト不在でTODO_001例外")
        void deleteProject_不在_TODO001例外() {
            // Given
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> projectService.deleteProject(PROJECT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_001"));
        }
    }

    // ========================================
    // completeProject
    // ========================================

    @Nested
    @DisplayName("completeProject")
    class CompleteProject {

        @Test
        @DisplayName("正常系: プロジェクトが完了になる")
        void completeProject_正常_完了() {
            // Given
            ProjectEntity project = createActiveProject();
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(projectRepository.save(any(ProjectEntity.class)))
                    .willAnswer(invocation -> {
                        ProjectEntity e = invocation.getArgument(0);
                        // Simulate @PrePersist since JPA callbacks don't fire in unit tests
                        java.lang.reflect.Method m = ProjectEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });
            given(milestoneRepository.countByProjectId(any())).willReturn(0L);
            given(milestoneRepository.countByProjectIdAndIsCompletedTrue(any())).willReturn(0L);

            // When
            ApiResponse<ProjectResponse> response = projectService.completeProject(PROJECT_ID);

            // Then
            assertThat(response.getData().getStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("異常系: 既に完了済みでTODO_005例外")
        void completeProject_既に完了_TODO005例外() {
            // Given
            ProjectEntity project = createCompletedProject();
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));

            // When / Then
            assertThatThrownBy(() -> projectService.completeProject(PROJECT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_005"));
        }
    }

    // ========================================
    // reopenProject
    // ========================================

    @Nested
    @DisplayName("reopenProject")
    class ReopenProject {

        @Test
        @DisplayName("正常系: 完了プロジェクトが再開される")
        void reopenProject_正常_再開() {
            // Given
            ProjectEntity project = createCompletedProject();
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(projectRepository.save(any(ProjectEntity.class)))
                    .willAnswer(invocation -> {
                        ProjectEntity e = invocation.getArgument(0);
                        // Simulate @PrePersist since JPA callbacks don't fire in unit tests
                        java.lang.reflect.Method m = ProjectEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });
            given(milestoneRepository.countByProjectId(any())).willReturn(0L);
            given(milestoneRepository.countByProjectIdAndIsCompletedTrue(any())).willReturn(0L);

            // When
            ApiResponse<ProjectResponse> response = projectService.reopenProject(PROJECT_ID);

            // Then
            assertThat(response.getData().getStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("異常系: 完了状態ではないプロジェクトでTODO_006例外")
        void reopenProject_未完了_TODO006例外() {
            // Given
            ProjectEntity project = createActiveProject();
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));

            // When / Then
            assertThatThrownBy(() -> projectService.reopenProject(PROJECT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_006"));
        }
    }

    // ========================================
    // listMilestones
    // ========================================

    @Nested
    @DisplayName("listMilestones")
    class ListMilestones {

        @Test
        @DisplayName("正常系: マイルストーン一覧が返却される")
        void listMilestones_正常_一覧返却() {
            // Given
            ProjectEntity project = createActiveProject();
            ProjectMilestoneEntity milestone = createMilestone();
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(milestoneRepository.findByProjectIdOrderBySortOrderAsc(PROJECT_ID))
                    .willReturn(List.of(milestone));

            // When
            ApiResponse<List<MilestoneResponse>> response = projectService.listMilestones(PROJECT_ID);

            // Then
            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getTitle()).isEqualTo("マイルストーン1");
        }
    }

    // ========================================
    // createMilestone
    // ========================================

    @Nested
    @DisplayName("createMilestone")
    class CreateMilestone {

        @Test
        @DisplayName("正常系: マイルストーンが作成される")
        void createMilestone_正常_作成成功() {
            // Given
            ProjectEntity project = createActiveProject();
            CreateMilestoneRequest request = new CreateMilestoneRequest(
                    "新マイルストーン", LocalDate.now().plusDays(10), (short) 1);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(milestoneRepository.countByProjectId(PROJECT_ID)).willReturn(5L);
            given(milestoneRepository.existsByProjectIdAndTitle(PROJECT_ID, "新マイルストーン")).willReturn(false);
            given(milestoneRepository.save(any(ProjectMilestoneEntity.class)))
                    .willAnswer(invocation -> {
                        ProjectMilestoneEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = ProjectMilestoneEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });

            // When
            ApiResponse<MilestoneResponse> response = projectService.createMilestone(PROJECT_ID, request);

            // Then
            assertThat(response.getData().getTitle()).isEqualTo("新マイルストーン");
            verify(milestoneRepository).save(any(ProjectMilestoneEntity.class));
        }

        @Test
        @DisplayName("異常系: マイルストーン上限超過でTODO_009例外")
        void createMilestone_上限超過_TODO009例外() {
            // Given
            // F02.7 設計書§6.5 に基づきマイルストーン上限は 50 件。
            // コミット 60797e44（fix(F02.7): マイルストーン上限を設計書通り 50 件に修正）で
            // ProjectService.MAX_MILESTONES_PER_PROJECT が 20 → 50 に変更済みのため、
            // テストのモック戻り値も設計書に合わせて 50L に追従する。
            ProjectEntity project = createActiveProject();
            CreateMilestoneRequest request = new CreateMilestoneRequest("上限超過", null, null);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(milestoneRepository.countByProjectId(PROJECT_ID)).willReturn(50L);

            // When / Then
            assertThatThrownBy(() -> projectService.createMilestone(PROJECT_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_009"));
        }

        @Test
        @DisplayName("異常系: 同名マイルストーン重複でTODO_008例外")
        void createMilestone_タイトル重複_TODO008例外() {
            // Given
            ProjectEntity project = createActiveProject();
            CreateMilestoneRequest request = new CreateMilestoneRequest("既存名", null, null);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(milestoneRepository.countByProjectId(PROJECT_ID)).willReturn(5L);
            given(milestoneRepository.existsByProjectIdAndTitle(PROJECT_ID, "既存名")).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> projectService.createMilestone(PROJECT_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_008"));
        }
    }

    // ========================================
    // updateMilestone
    // ========================================

    @Nested
    @DisplayName("updateMilestone")
    class UpdateMilestone {

        @Test
        @DisplayName("正常系: マイルストーンが更新される")
        void updateMilestone_正常_更新成功() {
            // Given
            ProjectEntity project = createActiveProject();
            ProjectMilestoneEntity milestone = createMilestone();
            UpdateMilestoneRequest request = new UpdateMilestoneRequest(
                    "更新名", LocalDate.now().plusDays(20), (short) 2);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(milestoneRepository.findByIdAndProjectId(MILESTONE_ID, PROJECT_ID))
                    .willReturn(Optional.of(milestone));
            given(milestoneRepository.existsByProjectIdAndTitleAndIdNot(PROJECT_ID, "更新名", MILESTONE_ID))
                    .willReturn(false);
            given(milestoneRepository.save(any(ProjectMilestoneEntity.class)))
                    .willAnswer(invocation -> {
                        ProjectMilestoneEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = ProjectMilestoneEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });

            // When
            ApiResponse<MilestoneResponse> response = projectService.updateMilestone(
                    PROJECT_ID, MILESTONE_ID, request);

            // Then
            assertThat(response.getData().getTitle()).isEqualTo("更新名");
        }

        @Test
        @DisplayName("異常系: マイルストーン不在でTODO_007例外")
        void updateMilestone_不在_TODO007例外() {
            // Given
            ProjectEntity project = createActiveProject();
            UpdateMilestoneRequest request = new UpdateMilestoneRequest("更新名", null, null);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(milestoneRepository.findByIdAndProjectId(MILESTONE_ID, PROJECT_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> projectService.updateMilestone(PROJECT_ID, MILESTONE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_007"));
        }

        @Test
        @DisplayName("異常系: タイトル変更時に重複でTODO_008例外")
        void updateMilestone_タイトル重複_TODO008例外() {
            // Given
            ProjectEntity project = createActiveProject();
            ProjectMilestoneEntity milestone = createMilestone();
            UpdateMilestoneRequest request = new UpdateMilestoneRequest("重複名", null, null);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(milestoneRepository.findByIdAndProjectId(MILESTONE_ID, PROJECT_ID))
                    .willReturn(Optional.of(milestone));
            given(milestoneRepository.existsByProjectIdAndTitleAndIdNot(PROJECT_ID, "重複名", MILESTONE_ID))
                    .willReturn(true);

            // When / Then
            assertThatThrownBy(() -> projectService.updateMilestone(PROJECT_ID, MILESTONE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_008"));
        }
    }

    // ========================================
    // deleteMilestone
    // ========================================

    @Nested
    @DisplayName("deleteMilestone")
    class DeleteMilestone {

        @Test
        @DisplayName("正常系: マイルストーンが削除される")
        void deleteMilestone_正常_削除() {
            // Given
            ProjectEntity project = createActiveProject();
            ProjectMilestoneEntity milestone = createMilestone();
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(milestoneRepository.findByIdAndProjectId(MILESTONE_ID, PROJECT_ID))
                    .willReturn(Optional.of(milestone));

            // When
            projectService.deleteMilestone(PROJECT_ID, MILESTONE_ID);

            // Then
            verify(milestoneRepository).delete(milestone);
        }

        @Test
        @DisplayName("異常系: マイルストーン不在でTODO_007例外")
        void deleteMilestone_不在_TODO007例外() {
            // Given
            ProjectEntity project = createActiveProject();
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(milestoneRepository.findByIdAndProjectId(MILESTONE_ID, PROJECT_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> projectService.deleteMilestone(PROJECT_ID, MILESTONE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_007"));
        }
    }

    // ========================================
    // completeMilestone
    // ========================================

    @Nested
    @DisplayName("completeMilestone")
    class CompleteMilestone {

        @Test
        @DisplayName("正常系: マイルストーンが完了になる")
        void completeMilestone_正常_完了() {
            // Given
            ProjectEntity project = createActiveProject();
            ProjectMilestoneEntity milestone = createMilestone();
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(milestoneRepository.findByIdAndProjectId(MILESTONE_ID, PROJECT_ID))
                    .willReturn(Optional.of(milestone));
            given(milestoneRepository.save(any(ProjectMilestoneEntity.class)))
                    .willAnswer(invocation -> {
                        ProjectMilestoneEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = ProjectMilestoneEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });

            // When
            ApiResponse<MilestoneResponse> response = projectService.completeMilestone(
                    PROJECT_ID, MILESTONE_ID);

            // Then
            assertThat(response.getData().isCompleted()).isTrue();
        }

        @Test
        @DisplayName("異常系: 既に完了済みでTODO_019例外")
        void completeMilestone_既に完了_TODO019例外() {
            // Given
            ProjectEntity project = createActiveProject();
            ProjectMilestoneEntity milestone = ProjectMilestoneEntity.builder()
                    .projectId(PROJECT_ID)
                    .title("完了済みマイルストーン")
                    .sortOrder((short) 1)
                    .isCompleted(true)
                    .completedAt(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(milestoneRepository.findByIdAndProjectId(MILESTONE_ID, PROJECT_ID))
                    .willReturn(Optional.of(milestone));

            // When / Then
            assertThatThrownBy(() -> projectService.completeMilestone(PROJECT_ID, MILESTONE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_019"));
        }
    }

    // ========================================
    // listTeamProjectsForUser（マイページ チームプロジェクト集約 /api/v1/me/team-projects）
    // ========================================

    /**
     * {@code ProjectService.listTeamProjectsForUser} の試練（red）。
     *
     * <p>受け入れ条件 AC-2〜AC-8 を純 Mockito で検証する。試練フェーズでは
     * {@code listTeamProjectsForUser} が空ページを返す<b>空実装</b>のため、
     * 所属チーム解決・集約クエリ・teamName/teamSlug 付与を検証する各テストは red になる。
     * /出陣 で本実装を行い green 化する。</p>
     */
    @Nested
    @DisplayName("listTeamProjectsForUser（チームプロジェクト集約）")
    class ListTeamProjectsForUser {

        private static final Long TEAM_A = 11L;
        private static final Long TEAM_B = 22L;
        private static final Long TEAM_C_NOT_JOINED = 99L;

        /** 指定 scopeId（チーム ID）のアクティブメンバーシップを生成する。 */
        private com.mannschaft.app.membership.entity.MembershipEntity membership(Long teamScopeId) {
            return com.mannschaft.app.membership.entity.MembershipEntity.builder()
                    .userId(USER_ID)
                    .scopeType(com.mannschaft.app.membership.domain.ScopeType.TEAM)
                    .scopeId(teamScopeId)
                    .roleKind(com.mannschaft.app.membership.domain.RoleKind.MEMBER)
                    .joinedAt(LocalDateTime.now())
                    .build();
        }

        /** 指定チーム（scopeId）に属する ACTIVE プロジェクトを生成する。 */
        private ProjectEntity teamProject(Long teamScopeId, String title) {
            return ProjectEntity.builder()
                    .scopeType(TodoScopeType.TEAM)
                    .scopeId(teamScopeId)
                    .title(title)
                    .emoji("📋")
                    .color("#FF0000")
                    .dueDate(LocalDate.now().plusDays(30))
                    .status(ProjectStatus.ACTIVE)
                    .progressRate(BigDecimal.ZERO)
                    .totalTodos((short) 0)
                    .completedTodos((short) 0)
                    .visibility(ProjectVisibility.MEMBERS_ONLY)
                    .createdBy(USER_ID)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
        }

        private void stubMilestoneCounts() {
            lenient().when(milestoneRepository.countByProjectId(any())).thenReturn(0L);
            lenient().when(milestoneRepository.countByProjectIdAndIsCompletedTrue(any())).thenReturn(0L);
        }

        @Test
        @DisplayName("AC-2: 複数チーム所属_所属teamId集合で集約クエリが呼ばれ全projが返る")
        void AC2_複数チーム所属_集約クエリのscopeId集合で全proj返却() {
            // Given: TEAM_A / TEAM_B の 2 チームに所属
            given(membershipRepository.findActiveByUserAndScopeType(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM))
                    .willReturn(List.of(membership(TEAM_A), membership(TEAM_B)));
            Page<ProjectEntity> page = new PageImpl<>(List.of(
                    teamProject(TEAM_A, "Aプロジェクト"),
                    teamProject(TEAM_B, "Bプロジェクト")));
            given(projectRepository.findByScopeTypeAndScopeIdInAndStatusAndDeletedAtIsNull(
                    eq(TodoScopeType.TEAM), anyCollection(), eq(ProjectStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(page);
            given(teamService.getNamesByIds(anyCollection()))
                    .willReturn(Map.of(TEAM_A, "チームA", TEAM_B, "チームB"));
            given(teamService.getSlugsByIds(anyCollection()))
                    .willReturn(Map.of(TEAM_A, "team-a", TEAM_B, "team-b"));
            stubMilestoneCounts();

            // When
            PagedResponse<com.mannschaft.app.todo.dto.TeamProjectSummaryResponse> response =
                    projectService.listTeamProjectsForUser(USER_ID, ProjectStatus.ACTIVE, 0, 20);

            // Then: 集約クエリが所属 teamId 集合 {A, B} で呼ばれ、全 proj が返る
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Collection<Long>> scopeIdsCaptor =
                    ArgumentCaptor.forClass(java.util.Collection.class);
            verify(projectRepository).findByScopeTypeAndScopeIdInAndStatusAndDeletedAtIsNull(
                    eq(TodoScopeType.TEAM), scopeIdsCaptor.capture(),
                    eq(ProjectStatus.ACTIVE), any(Pageable.class));
            assertThat(scopeIdsCaptor.getValue()).containsExactlyInAnyOrder(TEAM_A, TEAM_B);
            assertThat(response.getData()).hasSize(2);
            assertThat(response.getData()).extracting(
                    com.mannschaft.app.todo.dto.TeamProjectSummaryResponse::title)
                    .containsExactlyInAnyOrder("Aプロジェクト", "Bプロジェクト");
        }

        @Test
        @DisplayName("AC-3: 各レスポンスにteamId/teamName/teamSlugが正しく付与される")
        void AC3_teamId_teamName_teamSlugが付与される() {
            given(membershipRepository.findActiveByUserAndScopeType(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM))
                    .willReturn(List.of(membership(TEAM_A), membership(TEAM_B)));
            given(projectRepository.findByScopeTypeAndScopeIdInAndStatusAndDeletedAtIsNull(
                    eq(TodoScopeType.TEAM), anyCollection(), eq(ProjectStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(
                            teamProject(TEAM_A, "Aプロジェクト"),
                            teamProject(TEAM_B, "Bプロジェクト"))));
            given(teamService.getNamesByIds(anyCollection()))
                    .willReturn(Map.of(TEAM_A, "チームA", TEAM_B, "チームB"));
            given(teamService.getSlugsByIds(anyCollection()))
                    .willReturn(Map.of(TEAM_A, "team-a", TEAM_B, "team-b"));
            stubMilestoneCounts();

            PagedResponse<com.mannschaft.app.todo.dto.TeamProjectSummaryResponse> response =
                    projectService.listTeamProjectsForUser(USER_ID, ProjectStatus.ACTIVE, 0, 20);

            com.mannschaft.app.todo.dto.TeamProjectSummaryResponse a = response.getData().stream()
                    .filter(r -> "Aプロジェクト".equals(r.title())).findFirst().orElseThrow();
            assertThat(a.teamId()).isEqualTo(TEAM_A);
            assertThat(a.teamName()).isEqualTo("チームA");
            assertThat(a.teamSlug()).isEqualTo("team-a");
        }

        @Test
        @DisplayName("AC-4: membershipが返さないチームのprojは含まれない（scopeIds集合に無い）")
        void AC4_所属外チームのscopeIdは集約クエリに渡らない() {
            // Given: 所属は TEAM_A のみ（TEAM_C には未所属）
            given(membershipRepository.findActiveByUserAndScopeType(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM))
                    .willReturn(List.of(membership(TEAM_A)));
            given(projectRepository.findByScopeTypeAndScopeIdInAndStatusAndDeletedAtIsNull(
                    eq(TodoScopeType.TEAM), anyCollection(), eq(ProjectStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(teamProject(TEAM_A, "Aプロジェクト"))));
            given(teamService.getNamesByIds(anyCollection())).willReturn(Map.of(TEAM_A, "チームA"));
            given(teamService.getSlugsByIds(anyCollection())).willReturn(Map.of(TEAM_A, "team-a"));
            stubMilestoneCounts();

            projectService.listTeamProjectsForUser(USER_ID, ProjectStatus.ACTIVE, 0, 20);

            // Then: 集約クエリの scopeIds に未所属 TEAM_C は含まれない
            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Collection<Long>> scopeIdsCaptor =
                    ArgumentCaptor.forClass(java.util.Collection.class);
            verify(projectRepository).findByScopeTypeAndScopeIdInAndStatusAndDeletedAtIsNull(
                    eq(TodoScopeType.TEAM), scopeIdsCaptor.capture(),
                    eq(ProjectStatus.ACTIVE), any(Pageable.class));
            assertThat(scopeIdsCaptor.getValue()).containsExactly(TEAM_A);
            assertThat(scopeIdsCaptor.getValue()).doesNotContain(TEAM_C_NOT_JOINED);
        }

        @Test
        @DisplayName("AC-5: 非アクティブ/退会チームはfindActiveByUserAndScopeTypeが返さず除外される")
        void AC5_非アクティブ所属は集約対象に含まれない() {
            // Given: findActiveByUserAndScopeType は active な TEAM_A のみ返す
            //        （退会済み TEAM_B は left_at IS NOT NULL でリポジトリが除外する前提）
            given(membershipRepository.findActiveByUserAndScopeType(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM))
                    .willReturn(List.of(membership(TEAM_A)));
            given(projectRepository.findByScopeTypeAndScopeIdInAndStatusAndDeletedAtIsNull(
                    eq(TodoScopeType.TEAM), anyCollection(), eq(ProjectStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(teamProject(TEAM_A, "Aプロジェクト"))));
            given(teamService.getNamesByIds(anyCollection())).willReturn(Map.of(TEAM_A, "チームA"));
            given(teamService.getSlugsByIds(anyCollection())).willReturn(Map.of(TEAM_A, "team-a"));
            stubMilestoneCounts();

            projectService.listTeamProjectsForUser(USER_ID, ProjectStatus.ACTIVE, 0, 20);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Collection<Long>> scopeIdsCaptor =
                    ArgumentCaptor.forClass(java.util.Collection.class);
            verify(projectRepository).findByScopeTypeAndScopeIdInAndStatusAndDeletedAtIsNull(
                    eq(TodoScopeType.TEAM), scopeIdsCaptor.capture(),
                    eq(ProjectStatus.ACTIVE), any(Pageable.class));
            assertThat(scopeIdsCaptor.getValue()).containsExactly(TEAM_A);
            assertThat(scopeIdsCaptor.getValue()).doesNotContain(TEAM_B);
        }

        @Test
        @DisplayName("AC-6: status引数がfindBy...Statusに渡る（COMPLETED指定）")
        void AC6_status引数が集約クエリに渡る() {
            given(membershipRepository.findActiveByUserAndScopeType(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM))
                    .willReturn(List.of(membership(TEAM_A)));
            given(projectRepository.findByScopeTypeAndScopeIdInAndStatusAndDeletedAtIsNull(
                    eq(TodoScopeType.TEAM), anyCollection(), eq(ProjectStatus.COMPLETED), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));
            lenient().when(teamService.getNamesByIds(anyCollection())).thenReturn(Map.of());
            lenient().when(teamService.getSlugsByIds(anyCollection())).thenReturn(Map.of());
            stubMilestoneCounts();

            projectService.listTeamProjectsForUser(USER_ID, ProjectStatus.COMPLETED, 0, 20);

            // Then: status=COMPLETED が集約クエリに渡る
            verify(projectRepository).findByScopeTypeAndScopeIdInAndStatusAndDeletedAtIsNull(
                    eq(TodoScopeType.TEAM), anyCollection(),
                    eq(ProjectStatus.COMPLETED), any(Pageable.class));
        }

        @Test
        @DisplayName("AC-7: 所属0（membershipが空）→所属解決のみ実施し集約クエリは呼ばず空リスト")
        void AC7_所属0_集約クエリを呼ばず空リスト() {
            given(membershipRepository.findActiveByUserAndScopeType(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM))
                    .willReturn(List.of());

            PagedResponse<com.mannschaft.app.todo.dto.TeamProjectSummaryResponse> response =
                    projectService.listTeamProjectsForUser(USER_ID, ProjectStatus.ACTIVE, 0, 20);

            // Then: 所属解決（findActiveByUserAndScopeType）は実施するが、
            //       teamIds が空なので集約クエリは 1 度も呼ばず、空リストを返す。
            //       空実装段階では所属解決自体を呼ばないため red（出陣で所属解決を配線して green 化）。
            verify(membershipRepository).findActiveByUserAndScopeType(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM);
            verify(projectRepository, org.mockito.Mockito.never())
                    .findByScopeTypeAndScopeIdInAndStatusAndDeletedAtIsNull(
                            any(), anyCollection(), any(), any(Pageable.class));
            assertThat(response.getData()).isEmpty();
        }

        @Test
        @DisplayName("AC-8: teamName/slug解決はgetNamesByIds/getSlugsByIdsを各1回だけ呼ぶ（N+1でない）")
        void AC8_名前slug解決はバッチで各1回() {
            given(membershipRepository.findActiveByUserAndScopeType(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM))
                    .willReturn(List.of(membership(TEAM_A), membership(TEAM_B)));
            given(projectRepository.findByScopeTypeAndScopeIdInAndStatusAndDeletedAtIsNull(
                    eq(TodoScopeType.TEAM), anyCollection(), eq(ProjectStatus.ACTIVE), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(
                            teamProject(TEAM_A, "Aプロジェクト"),
                            teamProject(TEAM_B, "Bプロジェクト"))));
            given(teamService.getNamesByIds(anyCollection()))
                    .willReturn(Map.of(TEAM_A, "チームA", TEAM_B, "チームB"));
            given(teamService.getSlugsByIds(anyCollection()))
                    .willReturn(Map.of(TEAM_A, "team-a", TEAM_B, "team-b"));
            stubMilestoneCounts();

            projectService.listTeamProjectsForUser(USER_ID, ProjectStatus.ACTIVE, 0, 20);

            // Then: N+1 回避。proj が複数でも名前/slug 解決は各 1 回
            verify(teamService, org.mockito.Mockito.times(1)).getNamesByIds(anyCollection());
            verify(teamService, org.mockito.Mockito.times(1)).getSlugsByIds(anyCollection());
        }
    }
}
