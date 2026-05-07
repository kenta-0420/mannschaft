package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.TodoStatusBucket;
import com.mannschaft.app.todo.TodoStatusLabelScope;
import com.mannschaft.app.todo.dto.TodoStatusChangeRequest;
import com.mannschaft.app.todo.dto.TodoStatusChangeResponse;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.entity.TodoStatusLabelEntity;
import com.mannschaft.app.todo.repository.ProjectMilestoneRepository;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * TODOService.changeStatus のカスタムステータスラベル経路（F02.3.1 Phase 1a）の単体テスト。
 *
 * <ul>
 *   <li>statusLabelId 指定 → ラベルからバケット導出</li>
 *   <li>他スコープラベル指定 → LABEL_SCOPE_MISMATCH</li>
 *   <li>status と statusLabelId 両方指定 + バケット不一致 → STATUS_LABEL_BUCKET_MISMATCH</li>
 *   <li>status のみ指定（後方互換） → 従来通り動作、ラベル更新なし</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TodoService.changeStatus カスタムラベル経路テスト")
class TodoServiceChangeStatusWithLabelTest {

    private static final Long TODO_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long LABEL_ID = 50L;

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
    private NameResolverService nameResolverService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private TodoProgressService todoProgressService;
    @Mock
    private MilestoneGateService milestoneGateService;
    @Mock
    private TodoStatusLabelService todoStatusLabelService;

    @InjectMocks
    private TodoService todoService;

    @Test
    @DisplayName("正常系: statusLabelId 指定でラベルからバケット → status を導出")
    void changeStatus_ラベル経由でバケット導出() {
        TodoEntity todo = createOpenPersonalTodo();
        TodoStatusLabelEntity label = personalLabel(LABEL_ID, "レビュー中", TodoStatusBucket.IN_PROGRESS);
        given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
        given(todoStatusLabelService.findActiveById(LABEL_ID)).willReturn(label);
        given(todoRepository.save(any(TodoEntity.class))).willAnswer(inv -> inv.getArgument(0));

        TodoStatusChangeRequest request = new TodoStatusChangeRequest(null, LABEL_ID);

        ApiResponse<TodoStatusChangeResponse> response = todoService.changeStatus(
                TODO_ID, request, USER_ID);

        assertThat(response.getData().getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(todo.getStatusLabelId()).isEqualTo(LABEL_ID);
    }

    @Test
    @DisplayName("異常系: 他スコープのラベル指定は LABEL_SCOPE_MISMATCH")
    void changeStatus_他スコープラベル拒否() {
        TodoEntity todo = createOpenPersonalTodo();
        TodoStatusLabelEntity teamLabel = TodoStatusLabelEntity.builder()
                .id(LABEL_ID)
                .scopeType(TodoStatusLabelScope.TEAM)
                .scopeId(999L)
                .name("チーム専用")
                .bucket(TodoStatusBucket.IN_PROGRESS)
                .sortOrder(0)
                .isSystemDefault(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
        given(todoStatusLabelService.findActiveById(LABEL_ID)).willReturn(teamLabel);
        willThrow(new BusinessException(TodoErrorCode.LABEL_SCOPE_MISMATCH))
                .given(todoStatusLabelService).validateLabelForScope(teamLabel,
                        TodoScopeType.PERSONAL, USER_ID);

        TodoStatusChangeRequest request = new TodoStatusChangeRequest(null, LABEL_ID);

        assertThatThrownBy(() -> todoService.changeStatus(TODO_ID, request, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.LABEL_SCOPE_MISMATCH);
    }

    @Test
    @DisplayName("異常系: status と statusLabelId のバケット不一致は STATUS_LABEL_BUCKET_MISMATCH")
    void changeStatus_status_label_bucket不一致() {
        TodoEntity todo = createOpenPersonalTodo();
        TodoStatusLabelEntity label = personalLabel(LABEL_ID, "完了系", TodoStatusBucket.COMPLETED);
        given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
        given(todoStatusLabelService.findActiveById(LABEL_ID)).willReturn(label);

        // status=IN_PROGRESS だが label.bucket=COMPLETED → 不一致
        TodoStatusChangeRequest request = new TodoStatusChangeRequest("IN_PROGRESS", LABEL_ID);

        assertThatThrownBy(() -> todoService.changeStatus(TODO_ID, request, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TodoErrorCode.STATUS_LABEL_BUCKET_MISMATCH);
    }

    @Test
    @DisplayName("正常系: status のみ指定（後方互換）— ラベルは更新しない")
    void changeStatus_後方互換_statusのみ() {
        TodoEntity todo = createOpenPersonalTodo();
        given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
        given(todoRepository.save(any(TodoEntity.class))).willAnswer(inv -> inv.getArgument(0));

        TodoStatusChangeRequest request = new TodoStatusChangeRequest("IN_PROGRESS", null);

        ApiResponse<TodoStatusChangeResponse> response = todoService.changeStatus(
                TODO_ID, request, USER_ID);

        assertThat(response.getData().getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(todo.getStatusLabelId()).isNull();
    }

    private TodoEntity createOpenPersonalTodo() {
        return TodoEntity.builder()
                .id(TODO_ID)
                .scopeType(TodoScopeType.PERSONAL)
                .scopeId(USER_ID)
                .title("テストTODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .createdBy(USER_ID)
                .sortOrder(0)
                .milestoneLocked(false)
                .position(0)
                .depth(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private TodoStatusLabelEntity personalLabel(Long id, String name, TodoStatusBucket bucket) {
        return TodoStatusLabelEntity.builder()
                .id(id)
                .scopeType(TodoStatusLabelScope.PERSONAL)
                .scopeId(USER_ID)
                .name(name)
                .bucket(bucket)
                .sortOrder(0)
                .isSystemDefault(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
