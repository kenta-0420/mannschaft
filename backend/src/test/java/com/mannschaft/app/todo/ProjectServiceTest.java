package com.mannschaft.app.todo;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
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

    @InjectMocks
    private ProjectService projectService;

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
                    SCOPE_TYPE, SCOPE_ID, ProjectStatus.ACTIVE, 1, 20);

            // Then
            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getTitle()).isEqualTo("テストプロジェクト");
            assertThat(response.getMeta().getPage()).isEqualTo(1);
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
            ProjectEntity project = createActiveProject();
            CreateMilestoneRequest request = new CreateMilestoneRequest("上限超過", null, null);
            given(projectRepository.findByIdAndDeletedAtIsNull(PROJECT_ID)).willReturn(Optional.of(project));
            given(milestoneRepository.countByProjectId(PROJECT_ID)).willReturn(20L);

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
}
