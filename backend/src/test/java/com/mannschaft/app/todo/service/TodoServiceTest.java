package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.todo.dto.AddAssigneeRequest;
import com.mannschaft.app.todo.dto.AssigneeResponse;
import com.mannschaft.app.todo.dto.BulkStatusChangeRequest;
import com.mannschaft.app.todo.dto.CreateTodoRequest;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.dto.TodoStatusChangeRequest;
import com.mannschaft.app.todo.dto.TodoStatusChangeResponse;
import com.mannschaft.app.todo.dto.UpdateTodoRequest;
import com.mannschaft.app.todo.ProjectStatus;
import com.mannschaft.app.todo.ProjectVisibility;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.event.TodoStatusChangedEvent;
import com.mannschaft.app.todo.repository.ProjectMilestoneRepository;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TodoService} の単体テスト。
 * TODO CRUD・ステータス管理・担当者割り当てを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TodoService 単体テスト")
class TodoServiceTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private TodoAssigneeRepository assigneeRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMilestoneRepository milestoneRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private TodoService todoService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TODO_ID = 1L;
    private static final Long PROJECT_ID = 10L;
    private static final Long MILESTONE_ID = 20L;
    private static final Long SCOPE_ID = 100L;
    private static final Long USER_ID = 200L;
    private static final Long ASSIGNEE_USER_ID = 300L;
    private static final TodoScopeType SCOPE_TYPE = TodoScopeType.TEAM;

    private TodoEntity createOpenTodo() {
        return TodoEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .projectId(null)
                .milestoneId(null)
                .title("テストTODO")
                .description("テスト説明")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .dueDate(LocalDate.now().plusDays(7))
                .dueTime(LocalTime.of(17, 0))
                .sortOrder(0)
                .createdBy(USER_ID)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private TodoEntity createTodoWithProject() {
        return TodoEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .projectId(PROJECT_ID)
                .milestoneId(null)
                .title("プロジェクトTODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.HIGH)
                .sortOrder(1)
                .createdBy(USER_ID)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private ProjectEntity createProject() {
        return ProjectEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .title("テストプロジェクト")
                .status(ProjectStatus.ACTIVE)
                .progressRate(BigDecimal.ZERO)
                .totalTodos((short) 5)
                .completedTodos((short) 2)
                .visibility(ProjectVisibility.MEMBERS_ONLY)
                .createdBy(USER_ID)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private TodoAssigneeEntity createAssignee() {
        return TodoAssigneeEntity.builder()
                .todoId(TODO_ID)
                .userId(ASSIGNEE_USER_ID)
                .assignedBy(USER_ID)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ========================================
    // listTodos
    // ========================================

    @Nested
    @DisplayName("listTodos")
    class ListTodos {

        @Test
        @DisplayName("正常系: ステータス指定ありでTODO一覧が返却される")
        void listTodos_ステータス指定あり_一覧返却() {
            // Given
            TodoEntity todo = createOpenTodo();
            Page<TodoEntity> page = new PageImpl<>(List.of(todo));
            given(todoRepository.findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
                    eq(SCOPE_TYPE), eq(SCOPE_ID), eq(TodoStatus.OPEN), any(Pageable.class)))
                    .willReturn(page);
            given(assigneeRepository.findByTodoId(any())).willReturn(List.of());

            // When
            PagedResponse<TodoResponse> response = todoService.listTodos(
                    SCOPE_TYPE, SCOPE_ID, TodoStatus.OPEN, 1, 20);

            // Then
            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getTitle()).isEqualTo("テストTODO");
            assertThat(response.getMeta().getPage()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: ステータス指定なしで全件取得")
        void listTodos_ステータスなし_全件取得() {
            // Given
            Page<TodoEntity> page = new PageImpl<>(List.of());
            given(todoRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                    eq(SCOPE_TYPE), eq(SCOPE_ID), any(Pageable.class)))
                    .willReturn(page);

            // When
            PagedResponse<TodoResponse> response = todoService.listTodos(
                    SCOPE_TYPE, SCOPE_ID, null, 1, 20);

            // Then
            assertThat(response.getData()).isEmpty();
        }
    }

    // ========================================
    // listProjectTodos
    // ========================================

    @Nested
    @DisplayName("listProjectTodos")
    class ListProjectTodos {

        @Test
        @DisplayName("正常系: プロジェクト内TODO一覧が返却される")
        void listProjectTodos_正常_一覧返却() {
            // Given
            TodoEntity todo = createTodoWithProject();
            given(projectService.findProjectOrThrow(PROJECT_ID)).willReturn(createProject());
            given(todoRepository.findByProjectIdAndDeletedAtIsNullOrderBySortOrderAsc(PROJECT_ID))
                    .willReturn(List.of(todo));
            given(assigneeRepository.findByTodoId(any())).willReturn(List.of());

            // When
            ApiResponse<List<TodoResponse>> response = todoService.listProjectTodos(PROJECT_ID);

            // Then
            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getTitle()).isEqualTo("プロジェクトTODO");
        }
    }

    // ========================================
    // getTodo
    // ========================================

    @Nested
    @DisplayName("getTodo")
    class GetTodo {

        @Test
        @DisplayName("正常系: TODO詳細が返却される")
        void getTodo_正常_詳細返却() {
            // Given
            TodoEntity todo = createOpenTodo();
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(assigneeRepository.findByTodoId(any())).willReturn(List.of());

            // When
            ApiResponse<TodoResponse> response = todoService.getTodo(TODO_ID);

            // Then
            assertThat(response.getData().getTitle()).isEqualTo("テストTODO");
            assertThat(response.getData().getStatus()).isEqualTo("OPEN");
            assertThat(response.getData().getPriority()).isEqualTo("MEDIUM");
        }

        @Test
        @DisplayName("異常系: TODO不在でTODO_010例外")
        void getTodo_不在_TODO010例外() {
            // Given
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> todoService.getTodo(TODO_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_010"));
        }
    }

    // ========================================
    // createTodo
    // ========================================

    @Nested
    @DisplayName("createTodo")
    class CreateTodo {

        @Test
        @DisplayName("正常系: プロジェクトなしのTODOが作成される")
        void createTodo_プロジェクトなし_作成成功() {
            // Given
            CreateTodoRequest request = new CreateTodoRequest(
                    "新規TODO", "説明", null, null, "HIGH",
                    LocalDate.now().plusDays(5), null, null, null);
            given(todoRepository.save(any(TodoEntity.class)))
                    .willAnswer(invocation -> {
                        TodoEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = TodoEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });
            given(assigneeRepository.findByTodoId(any())).willReturn(List.of());

            // When
            ApiResponse<TodoResponse> response = todoService.createTodo(
                    SCOPE_TYPE, SCOPE_ID, request, USER_ID);

            // Then
            assertThat(response.getData().getTitle()).isEqualTo("新規TODO");
            assertThat(response.getData().getPriority()).isEqualTo("HIGH");
            verify(todoRepository).save(any(TodoEntity.class));
            verify(projectRepository, never()).recalculateProgress(any());
        }

        @Test
        @DisplayName("正常系: プロジェクト付きTODOが作成され進捗再計算される")
        void createTodo_プロジェクト付き_進捗再計算() {
            // Given
            ProjectEntity project = createProject();
            CreateTodoRequest request = new CreateTodoRequest(
                    "プロジェクトTODO", null, PROJECT_ID, null, null,
                    null, null, null, null);
            given(projectService.findProjectOrThrow(PROJECT_ID)).willReturn(project);
            given(todoRepository.save(any(TodoEntity.class)))
                    .willAnswer(invocation -> {
                        TodoEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = TodoEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });
            given(assigneeRepository.findByTodoId(any())).willReturn(List.of());

            // When
            ApiResponse<TodoResponse> response = todoService.createTodo(
                    SCOPE_TYPE, SCOPE_ID, request, USER_ID);

            // Then
            assertThat(response.getData().getTitle()).isEqualTo("プロジェクトTODO");
            verify(projectRepository).recalculateProgress(PROJECT_ID);
        }

        @Test
        @DisplayName("正常系: 担当者付きTODOが作成される")
        void createTodo_担当者付き_作成成功() {
            // Given
            CreateTodoRequest request = new CreateTodoRequest(
                    "担当者付きTODO", null, null, null, null,
                    null, null, null, List.of(ASSIGNEE_USER_ID));
            given(todoRepository.save(any(TodoEntity.class)))
                    .willAnswer(invocation -> {
                        TodoEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = TodoEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });
            given(assigneeRepository.save(any(TodoAssigneeEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(assigneeRepository.findByTodoId(any())).willReturn(List.of());

            // When
            todoService.createTodo(SCOPE_TYPE, SCOPE_ID, request, USER_ID);

            // Then
            verify(assigneeRepository).save(any(TodoAssigneeEntity.class));
        }

        @Test
        @DisplayName("異常系: プロジェクトとスコープ不一致でTODO_011例外")
        void createTodo_スコープ不一致_TODO011例外() {
            // Given
            ProjectEntity project = ProjectEntity.builder()
                    .scopeType(TodoScopeType.PERSONAL)
                    .scopeId(999L)
                    .title("別スコープ")
                    .status(ProjectStatus.ACTIVE)
                    .progressRate(BigDecimal.ZERO)
                    .totalTodos((short) 0)
                    .completedTodos((short) 0)
                    .visibility(ProjectVisibility.PRIVATE)
                    .createdBy(USER_ID)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            CreateTodoRequest request = new CreateTodoRequest(
                    "TODO", null, PROJECT_ID, null, null,
                    null, null, null, null);
            given(projectService.findProjectOrThrow(PROJECT_ID)).willReturn(project);

            // When / Then
            assertThatThrownBy(() -> todoService.createTodo(SCOPE_TYPE, SCOPE_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_011"));
        }

        @Test
        @DisplayName("異常系: プロジェクトなしでマイルストーン指定でTODO_013例外")
        void createTodo_マイルストーンプロジェクト不要_TODO013例外() {
            // Given
            CreateTodoRequest request = new CreateTodoRequest(
                    "TODO", null, null, MILESTONE_ID, null,
                    null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> todoService.createTodo(SCOPE_TYPE, SCOPE_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_013"));
        }

        @Test
        @DisplayName("異常系: マイルストーンがプロジェクトに属さないでTODO_012例外")
        void createTodo_マイルストーン不整合_TODO012例外() {
            // Given
            ProjectEntity project = createProject();
            CreateTodoRequest request = new CreateTodoRequest(
                    "TODO", null, PROJECT_ID, MILESTONE_ID, null,
                    null, null, null, null);
            given(projectService.findProjectOrThrow(PROJECT_ID)).willReturn(project);
            given(milestoneRepository.findByIdAndProjectId(MILESTONE_ID, PROJECT_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> todoService.createTodo(SCOPE_TYPE, SCOPE_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_012"));
        }

        @Test
        @DisplayName("正常系: priority未指定時にMEDIUMがデフォルト")
        void createTodo_priority未指定_MEDIUMデフォルト() {
            // Given
            CreateTodoRequest request = new CreateTodoRequest(
                    "デフォルト優先度TODO", null, null, null, null,
                    null, null, null, null);
            given(todoRepository.save(any(TodoEntity.class)))
                    .willAnswer(invocation -> {
                        TodoEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = TodoEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });
            given(assigneeRepository.findByTodoId(any())).willReturn(List.of());

            // When
            ApiResponse<TodoResponse> response = todoService.createTodo(
                    SCOPE_TYPE, SCOPE_ID, request, USER_ID);

            // Then
            assertThat(response.getData().getPriority()).isEqualTo("MEDIUM");
        }
    }

    // ========================================
    // updateTodo
    // ========================================

    @Nested
    @DisplayName("updateTodo")
    class UpdateTodo {

        @Test
        @DisplayName("正常系: TODOが更新される")
        void updateTodo_正常_更新成功() {
            // Given
            TodoEntity todo = createOpenTodo();
            UpdateTodoRequest request = new UpdateTodoRequest(
                    "更新タイトル", "更新説明", null, null, "URGENT",
                    LocalDate.now().plusDays(3), LocalTime.of(12, 0), 5);
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(todoRepository.save(any(TodoEntity.class)))
                    .willAnswer(invocation -> {
                        TodoEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = TodoEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });
            given(assigneeRepository.findByTodoId(any())).willReturn(List.of());

            // When
            ApiResponse<TodoResponse> response = todoService.updateTodo(TODO_ID, request);

            // Then
            assertThat(response.getData().getTitle()).isEqualTo("更新タイトル");
            assertThat(response.getData().getPriority()).isEqualTo("URGENT");
        }

        @Test
        @DisplayName("異常系: 新プロジェクトとスコープ不一致でTODO_011例外")
        void updateTodo_スコープ不一致_TODO011例外() {
            // Given
            TodoEntity todo = createOpenTodo();
            ProjectEntity wrongScopeProject = ProjectEntity.builder()
                    .scopeType(TodoScopeType.ORGANIZATION)
                    .scopeId(999L)
                    .title("別スコープ")
                    .status(ProjectStatus.ACTIVE)
                    .progressRate(BigDecimal.ZERO)
                    .totalTodos((short) 0)
                    .completedTodos((short) 0)
                    .visibility(ProjectVisibility.MEMBERS_ONLY)
                    .createdBy(USER_ID)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            UpdateTodoRequest request = new UpdateTodoRequest(
                    "更新", null, PROJECT_ID, null, null, null, null, null);
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(projectService.findProjectOrThrow(PROJECT_ID)).willReturn(wrongScopeProject);

            // When / Then
            assertThatThrownBy(() -> todoService.updateTodo(TODO_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_011"));
        }

        @Test
        @DisplayName("異常系: TODO不在でTODO_010例外")
        void updateTodo_不在_TODO010例外() {
            // Given
            UpdateTodoRequest request = new UpdateTodoRequest(
                    "更新", null, null, null, null, null, null, null);
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> todoService.updateTodo(TODO_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_010"));
        }
    }

    // ========================================
    // deleteTodo
    // ========================================

    @Nested
    @DisplayName("deleteTodo")
    class DeleteTodo {

        @Test
        @DisplayName("正常系: TODOが論理削除される")
        void deleteTodo_正常_論理削除() {
            // Given
            TodoEntity todo = createOpenTodo();
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));

            // When
            todoService.deleteTodo(TODO_ID);

            // Then
            assertThat(todo.getDeletedAt()).isNotNull();
            verify(todoRepository).save(todo);
        }

        @Test
        @DisplayName("正常系: プロジェクト付きTODO削除時に進捗再計算される")
        void deleteTodo_プロジェクト付き_進捗再計算() {
            // Given
            TodoEntity todo = createTodoWithProject();
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));

            // When
            todoService.deleteTodo(TODO_ID);

            // Then
            verify(projectRepository).recalculateProgress(PROJECT_ID);
        }

        @Test
        @DisplayName("異常系: TODO不在でTODO_010例外")
        void deleteTodo_不在_TODO010例外() {
            // Given
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> todoService.deleteTodo(TODO_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_010"));
        }
    }

    // ========================================
    // changeStatus
    // ========================================

    @Nested
    @DisplayName("changeStatus")
    class ChangeStatus {

        @Test
        @DisplayName("正常系: ステータスがCOMPLETEDに変更されイベント発行される")
        void changeStatus_正常_COMPLETED() {
            // Given
            TodoEntity todo = createOpenTodo();
            TodoStatusChangeRequest request = new TodoStatusChangeRequest("COMPLETED");
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(todoRepository.save(any(TodoEntity.class)))
                    .willAnswer(invocation -> {
                        TodoEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = TodoEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });

            // When
            ApiResponse<TodoStatusChangeResponse> response = todoService.changeStatus(
                    TODO_ID, request, USER_ID);

            // Then
            assertThat(response.getData().getStatus()).isEqualTo("COMPLETED");
            verify(eventPublisher).publishEvent(any(TodoStatusChangedEvent.class));
        }

        @Test
        @DisplayName("正常系: プロジェクト付きTODOの完了で進捗再計算される")
        void changeStatus_プロジェクト付き_進捗再計算() {
            // Given
            TodoEntity todo = createTodoWithProject();
            ProjectEntity project = createProject();
            TodoStatusChangeRequest request = new TodoStatusChangeRequest("COMPLETED");
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(todoRepository.save(any(TodoEntity.class)))
                    .willAnswer(invocation -> {
                        TodoEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = TodoEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });
            given(projectService.findProjectOrThrow(PROJECT_ID)).willReturn(project);

            // When
            ApiResponse<TodoStatusChangeResponse> response = todoService.changeStatus(
                    TODO_ID, request, USER_ID);

            // Then
            assertThat(response.getData().getProjectProgress()).isNotNull();
            verify(projectRepository).recalculateProgress(PROJECT_ID);
        }

        @Test
        @DisplayName("正常系: IN_PROGRESSへの変更")
        void changeStatus_正常_IN_PROGRESS() {
            // Given
            TodoEntity todo = createOpenTodo();
            TodoStatusChangeRequest request = new TodoStatusChangeRequest("IN_PROGRESS");
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(todoRepository.save(any(TodoEntity.class)))
                    .willAnswer(invocation -> {
                        TodoEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = TodoEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });

            // When
            ApiResponse<TodoStatusChangeResponse> response = todoService.changeStatus(
                    TODO_ID, request, USER_ID);

            // Then
            assertThat(response.getData().getStatus()).isEqualTo("IN_PROGRESS");
        }
    }

    // ========================================
    // bulkChangeStatus
    // ========================================

    @Nested
    @DisplayName("bulkChangeStatus")
    class BulkChangeStatus {

        @Test
        @DisplayName("正常系: 複数TODOのステータスが一括変更される")
        void bulkChangeStatus_正常_一括変更() {
            // Given
            TodoEntity todo1 = createOpenTodo();
            TodoEntity todo2 = createOpenTodo();
            BulkStatusChangeRequest request = new BulkStatusChangeRequest(
                    List.of(1L, 2L), "COMPLETED");
            given(todoRepository.findByIdInAndDeletedAtIsNull(List.of(1L, 2L)))
                    .willReturn(List.of(todo1, todo2));
            given(todoRepository.save(any(TodoEntity.class)))
                    .willAnswer(invocation -> {
                        TodoEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = TodoEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });

            // When
            ApiResponse<List<TodoStatusChangeResponse>> response = todoService.bulkChangeStatus(
                    SCOPE_TYPE, SCOPE_ID, request, USER_ID);

            // Then
            assertThat(response.getData()).hasSize(2);
            assertThat(response.getData().get(0).getStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("異常系: 一括操作サイズ超過でTODO_018例外")
        void bulkChangeStatus_サイズ超過_TODO018例外() {
            // Given
            List<Long> ids = new java.util.ArrayList<>();
            for (long i = 1; i <= 51; i++) {
                ids.add(i);
            }
            BulkStatusChangeRequest request = new BulkStatusChangeRequest(ids, "COMPLETED");

            // When / Then
            assertThatThrownBy(() -> todoService.bulkChangeStatus(
                    SCOPE_TYPE, SCOPE_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_018"));
        }
    }

    // ========================================
    // getMyTodos
    // ========================================

    @Nested
    @DisplayName("getMyTodos")
    class GetMyTodos {

        @Test
        @DisplayName("正常系: 自分のTODO一覧が返却される")
        void getMyTodos_正常_一覧返却() {
            // Given
            TodoEntity todo = createOpenTodo();
            given(todoRepository.findMyTodos(USER_ID)).willReturn(List.of(todo));
            given(assigneeRepository.findByTodoId(any())).willReturn(List.of());

            // When
            ApiResponse<List<TodoResponse>> response = todoService.getMyTodos(USER_ID);

            // Then
            assertThat(response.getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 割り当てなしで空リスト返却")
        void getMyTodos_割り当てなし_空リスト() {
            // Given
            given(todoRepository.findMyTodos(USER_ID)).willReturn(List.of());

            // When
            ApiResponse<List<TodoResponse>> response = todoService.getMyTodos(USER_ID);

            // Then
            assertThat(response.getData()).isEmpty();
        }
    }

    // ========================================
    // addAssignee
    // ========================================

    @Nested
    @DisplayName("addAssignee")
    class AddAssignee {

        @Test
        @DisplayName("正常系: 担当者が追加される")
        void addAssignee_正常_追加成功() {
            // Given
            TodoEntity todo = createOpenTodo();
            AddAssigneeRequest request = new AddAssigneeRequest(ASSIGNEE_USER_ID);
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(assigneeRepository.existsByTodoIdAndUserId(TODO_ID, ASSIGNEE_USER_ID)).willReturn(false);
            given(assigneeRepository.save(any(TodoAssigneeEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<AssigneeResponse> response = todoService.addAssignee(TODO_ID, request, USER_ID);

            // Then
            assertThat(response.getData().getUserId()).isEqualTo(ASSIGNEE_USER_ID);
            verify(assigneeRepository).save(any(TodoAssigneeEntity.class));
        }

        @Test
        @DisplayName("異常系: 担当者が既に割り当て済みでTODO_014例外")
        void addAssignee_重複_TODO014例外() {
            // Given
            TodoEntity todo = createOpenTodo();
            AddAssigneeRequest request = new AddAssigneeRequest(ASSIGNEE_USER_ID);
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(assigneeRepository.existsByTodoIdAndUserId(TODO_ID, ASSIGNEE_USER_ID)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> todoService.addAssignee(TODO_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_014"));
        }

        @Test
        @DisplayName("異常系: TODO不在でTODO_010例外")
        void addAssignee_TODO不在_TODO010例外() {
            // Given
            AddAssigneeRequest request = new AddAssigneeRequest(ASSIGNEE_USER_ID);
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> todoService.addAssignee(TODO_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_010"));
        }
    }

    // ========================================
    // removeAssignee
    // ========================================

    @Nested
    @DisplayName("removeAssignee")
    class RemoveAssignee {

        @Test
        @DisplayName("正常系: 担当者が削除される")
        void removeAssignee_正常_削除() {
            // Given
            TodoEntity todo = createOpenTodo();
            TodoAssigneeEntity assignee = createAssignee();
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(assigneeRepository.findByTodoIdAndUserId(TODO_ID, ASSIGNEE_USER_ID))
                    .willReturn(Optional.of(assignee));

            // When
            todoService.removeAssignee(TODO_ID, ASSIGNEE_USER_ID);

            // Then
            verify(assigneeRepository).delete(assignee);
        }

        @Test
        @DisplayName("異常系: 担当者不在でTODO_015例外")
        void removeAssignee_不在_TODO015例外() {
            // Given
            TodoEntity todo = createOpenTodo();
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(assigneeRepository.findByTodoIdAndUserId(TODO_ID, ASSIGNEE_USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> todoService.removeAssignee(TODO_ID, ASSIGNEE_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_015"));
        }
    }
}
