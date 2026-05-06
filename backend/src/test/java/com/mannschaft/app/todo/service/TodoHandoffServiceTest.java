package com.mannschaft.app.todo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.TodoStatusBucket;
import com.mannschaft.app.todo.TodoStatusLabelScope;
import com.mannschaft.app.todo.dto.TodoHandoffRequest;
import com.mannschaft.app.todo.dto.TodoHandoffResponse;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.entity.TodoHandoffEntity;
import com.mannschaft.app.todo.entity.TodoStatusLabelEntity;
import com.mannschaft.app.todo.event.TodoHandoffEvent;
import com.mannschaft.app.todo.event.TodoStatusChangedEvent;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoHandoffRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TodoHandoffService} の単体テスト（F02.3.1 Phase 2）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TodoHandoffService 単体テスト")
class TodoHandoffServiceTest {

    @Mock private TodoRepository todoRepository;
    @Mock private TodoAssigneeRepository assigneeRepository;
    @Mock private TodoHandoffRepository handoffRepository;
    @Mock private TodoStatusLabelService labelService;
    @Mock private AccessControlService accessControlService;
    @Mock private NameResolverService nameResolverService;
    @Mock private AuditLogService auditLogService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private TodoHandoffService handoffService;

    private static final Long TEAM_ID = 100L;
    private static final Long ACTOR_ID = 1L;
    private static final Long TODO_ID = 50L;

    private TodoEntity teamTodo;
    private TodoStatusLabelEntity inProgressLabel;

    @BeforeEach
    void setUp() {
        teamTodo = TodoEntity.builder()
                .id(TODO_ID)
                .scopeType(TodoScopeType.TEAM)
                .scopeId(TEAM_ID)
                .title("レビュー資料を準備")
                .status(TodoStatus.OPEN)
                .priority(com.mannschaft.app.todo.TodoPriority.MEDIUM)
                .sortOrder(0)
                .createdBy(ACTOR_ID)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        inProgressLabel = TodoStatusLabelEntity.builder()
                .id(10L)
                .scopeType(TodoStatusLabelScope.TEAM)
                .scopeId(TEAM_ID)
                .name("レビュー中")
                .bucket(TodoStatusBucket.IN_PROGRESS)
                .color("#3b82f6")
                .sortOrder(1)
                .isSystemDefault(false)
                .build();
    }

    @Test
    @DisplayName("正常系: assignees 置換 + status/label 更新 + 履歴 1 行追加 + 通知イベント発行")
    void handoff_success() {
        Long toUser = 2L;
        TodoHandoffRequest req = new TodoHandoffRequest(List.of(toUser), 10L, "確認お願いします");

        given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(teamTodo));
        given(accessControlService.isMember(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(accessControlService.isMember(toUser, TEAM_ID, "TEAM")).willReturn(true);
        given(labelService.findActiveById(10L)).willReturn(inProgressLabel);
        // 操作前 assignees: 操作者本人のみ
        TodoAssigneeEntity oldAssignee = TodoAssigneeEntity.builder()
                .id(901L).todoId(TODO_ID).userId(ACTOR_ID).assignedBy(ACTOR_ID).createdAt(LocalDateTime.now()).build();
        given(assigneeRepository.findByTodoId(TODO_ID)).willReturn(List.of(oldAssignee));
        given(todoRepository.save(any(TodoEntity.class))).willAnswer(inv -> inv.getArgument(0));
        given(handoffRepository.save(any(TodoHandoffEntity.class))).willAnswer(inv -> {
            TodoHandoffEntity e = inv.getArgument(0);
            return e.toBuilder().id(7000L).createdAt(LocalDateTime.now()).build();
        });
        lenient().when(nameResolverService.resolveUserDisplayNames(any()))
                .thenReturn(Map.of(ACTOR_ID, "健太", toUser, "山田"));

        ApiResponse<TodoHandoffResponse> result = handoffService.handoff(
                TodoScopeType.TEAM, TEAM_ID, TODO_ID, req, ACTOR_ID);

        // assignees の置換: 削除→挿入
        verify(assigneeRepository).deleteAll(List.of(oldAssignee));
        ArgumentCaptor<TodoAssigneeEntity> assigneeCap = ArgumentCaptor.forClass(TodoAssigneeEntity.class);
        verify(assigneeRepository).save(assigneeCap.capture());
        assertThat(assigneeCap.getValue().getUserId()).isEqualTo(toUser);

        // todo.changeStatusWithLabel(...) → status: IN_PROGRESS, statusLabelId: 10
        verify(todoRepository).save(any(TodoEntity.class));
        assertThat(teamTodo.getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(teamTodo.getStatusLabelId()).isEqualTo(10L);

        // 履歴 1 行追加
        ArgumentCaptor<TodoHandoffEntity> handoffCap = ArgumentCaptor.forClass(TodoHandoffEntity.class);
        verify(handoffRepository).save(handoffCap.capture());
        TodoHandoffEntity saved = handoffCap.getValue();
        assertThat(saved.getTodoId()).isEqualTo(TODO_ID);
        assertThat(saved.getFromUserId()).isEqualTo(ACTOR_ID);
        assertThat(saved.getNewStatus()).isEqualTo("IN_PROGRESS");
        assertThat(saved.getNewStatusLabelId()).isEqualTo(10L);
        assertThat(saved.getNewStatusLabelName()).isEqualTo("レビュー中");
        assertThat(saved.getMessage()).isEqualTo("確認お願いします");
        assertThat(saved.getFromAssigneeUserIds()).contains("[1]");
        assertThat(saved.getToAssigneeUserIds()).contains("[2]");

        // イベント発行: TodoHandoffEvent + TodoStatusChangedEvent(fromHandoff=true)
        verify(eventPublisher).publishEvent(any(TodoHandoffEvent.class));
        ArgumentCaptor<TodoStatusChangedEvent> statusEvent = ArgumentCaptor.forClass(TodoStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(statusEvent.capture());
        assertThat(statusEvent.getValue().isFromHandoff()).isTrue();

        // 監査ログ
        verify(auditLogService).record(eq("TODO_HANDED_OFF"), eq(ACTOR_ID), isNull(),
                eq(TEAM_ID), isNull(), isNull(), isNull(), isNull(), any());

        // レスポンス
        assertThat(result.getData().id()).isEqualTo(7000L);
        assertThat(result.getData().newStatus()).isEqualTo("IN_PROGRESS");
        assertThat(result.getData().newStatusLabel().name()).isEqualTo("レビュー中");
        assertThat(result.getData().message()).isEqualTo("確認お願いします");
    }

    @Test
    @DisplayName("個人スコープではキャッチボール不可（HANDOFF_NOT_ALLOWED_FOR_PERSONAL）")
    void handoff_personal_rejected() {
        TodoHandoffRequest req = new TodoHandoffRequest(List.of(2L), 10L, null);

        assertThatThrownBy(() -> handoffService.handoff(
                TodoScopeType.PERSONAL, ACTOR_ID, TODO_ID, req, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(TodoErrorCode.HANDOFF_NOT_ALLOWED_FOR_PERSONAL);

        verify(todoRepository, never()).findByIdAndDeletedAtIsNull(anyLong());
    }

    @Test
    @DisplayName("宛先がスコープ非メンバーなら HANDOFF_INVALID_RECIPIENT")
    void handoff_invalid_recipient() {
        Long invalidUser = 999L;
        TodoHandoffRequest req = new TodoHandoffRequest(List.of(invalidUser), 10L, null);

        given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(teamTodo));
        given(accessControlService.isMember(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(accessControlService.isMember(invalidUser, TEAM_ID, "TEAM")).willReturn(false);

        assertThatThrownBy(() -> handoffService.handoff(
                TodoScopeType.TEAM, TEAM_ID, TODO_ID, req, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(TodoErrorCode.HANDOFF_INVALID_RECIPIENT);

        verify(handoffRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("自己 handoff (操作者だけが宛先) は許容され、TodoHandoffEvent も発火する（通知抑制は Listener 側）")
    void handoff_to_self_allowed() {
        TodoHandoffRequest req = new TodoHandoffRequest(List.of(ACTOR_ID), 10L, "状況メモ");

        given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(teamTodo));
        given(accessControlService.isMember(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(labelService.findActiveById(10L)).willReturn(inProgressLabel);
        given(assigneeRepository.findByTodoId(TODO_ID)).willReturn(List.of());
        given(todoRepository.save(any(TodoEntity.class))).willAnswer(inv -> inv.getArgument(0));
        given(handoffRepository.save(any(TodoHandoffEntity.class))).willAnswer(inv -> {
            TodoHandoffEntity e = inv.getArgument(0);
            return e.toBuilder().id(7001L).createdAt(LocalDateTime.now()).build();
        });
        lenient().when(nameResolverService.resolveUserDisplayNames(any()))
                .thenReturn(Map.of(ACTOR_ID, "健太"));

        ApiResponse<TodoHandoffResponse> result = handoffService.handoff(
                TodoScopeType.TEAM, TEAM_ID, TODO_ID, req, ACTOR_ID);

        assertThat(result.getData()).isNotNull();
        verify(handoffRepository).save(any());
        verify(eventPublisher).publishEvent(any(TodoHandoffEvent.class));
    }

    @Test
    @DisplayName("ラベルが TODO スコープと不一致なら STATUS_LABEL_SCOPE_MISMATCH")
    void handoff_label_scope_mismatch() {
        Long toUser = 2L;
        TodoHandoffRequest req = new TodoHandoffRequest(List.of(toUser), 10L, null);

        given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(teamTodo));
        given(accessControlService.isMember(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(accessControlService.isMember(toUser, TEAM_ID, "TEAM")).willReturn(true);
        given(labelService.findActiveById(10L)).willReturn(inProgressLabel);
        // labelService 側に validateLabelForScope の例外を投げさせる
        org.mockito.BDDMockito.willThrow(new BusinessException(TodoErrorCode.STATUS_LABEL_SCOPE_MISMATCH))
                .given(labelService).validateLabelForScope(inProgressLabel, TodoScopeType.TEAM, TEAM_ID);

        assertThatThrownBy(() -> handoffService.handoff(
                TodoScopeType.TEAM, TEAM_ID, TODO_ID, req, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(TodoErrorCode.STATUS_LABEL_SCOPE_MISMATCH);

        verify(handoffRepository, never()).save(any());
    }

    @Test
    @DisplayName("操作者がスコープ非メンバーなら TODO_NOT_FOUND（IDOR ガード）")
    void handoff_actor_not_member() {
        Long toUser = 2L;
        TodoHandoffRequest req = new TodoHandoffRequest(List.of(toUser), 10L, null);

        given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(teamTodo));
        given(accessControlService.isMember(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(false);

        assertThatThrownBy(() -> handoffService.handoff(
                TodoScopeType.TEAM, TEAM_ID, TODO_ID, req, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(TodoErrorCode.TODO_NOT_FOUND);
    }

    @Test
    @DisplayName("TODO スコープが path と不一致なら TODO_NOT_FOUND（IDOR ガード）")
    void handoff_scope_mismatch() {
        TodoHandoffRequest req = new TodoHandoffRequest(List.of(2L), 10L, null);
        // teamTodo は scopeId=TEAM_ID(100) だが path で 999 を指定 → fail
        given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(teamTodo));

        assertThatThrownBy(() -> handoffService.handoff(
                TodoScopeType.TEAM, 999L, TODO_ID, req, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(TodoErrorCode.TODO_NOT_FOUND);
    }

    @Test
    @DisplayName("listHistory: スコープ整合 + メンバーシップ確認 OK で履歴を新しい順に返す")
    void listHistory_success() {
        given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(teamTodo));
        given(accessControlService.isMember(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(true);

        TodoHandoffEntity h1 = TodoHandoffEntity.builder()
                .id(1L).todoId(TODO_ID).fromUserId(ACTOR_ID)
                .fromAssigneeUserIds("[1]").toAssigneeUserIds("[2]")
                .previousStatus("OPEN").newStatus("IN_PROGRESS")
                .newStatusLabelId(10L).newStatusLabelName("レビュー中")
                .createdAt(LocalDateTime.now())
                .build();
        given(handoffRepository.findByTodoIdOrderByCreatedAtDesc(TODO_ID))
                .willReturn(List.of(h1));
        given(labelService.findActiveByIds(any())).willReturn(List.of(inProgressLabel));
        lenient().when(nameResolverService.resolveUserDisplayNames(any()))
                .thenReturn(Map.of(ACTOR_ID, "健太", 2L, "山田"));

        ApiResponse<List<TodoHandoffResponse>> result = handoffService.listHistory(
                TodoScopeType.TEAM, TEAM_ID, TODO_ID, ACTOR_ID);
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).newStatusLabel().name()).isEqualTo("レビュー中");
    }

    @Test
    @DisplayName("listHistory: 個人スコープでは TODO_NOT_FOUND")
    void listHistory_personal_rejected() {
        assertThatThrownBy(() -> handoffService.listHistory(
                TodoScopeType.PERSONAL, ACTOR_ID, TODO_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(TodoErrorCode.TODO_NOT_FOUND);
    }
}
