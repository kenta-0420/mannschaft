package com.mannschaft.app.todo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatusBucket;
import com.mannschaft.app.todo.TodoStatusLabelScope;
import com.mannschaft.app.todo.dto.CreateTodoStatusLabelRequest;
import com.mannschaft.app.todo.dto.TodoStatusLabelResponse;
import com.mannschaft.app.todo.dto.UpdateTodoStatusLabelRequest;
import com.mannschaft.app.todo.entity.TodoStatusLabelEntity;
import com.mannschaft.app.todo.repository.TodoStatusLabelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TodoStatusLabelService} 単体テスト（F02.3.1 Phase 1a）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TodoStatusLabelService 単体テスト")
class TodoStatusLabelServiceTest {

    @Mock
    private TodoStatusLabelRepository labelRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TodoStatusLabelService labelService;

    /**
     * issue #2544: 本番では {@code @Autowired @Lazy} で注入される自己プロキシ {@code self} を、
     * 純 Mockito UT では自分自身で埋める（キャッシュプロキシは介在しないので挙動は従来どおり）。
     * 埋めないと自己プロキシ経由の呼び出しが NPE になる。
     */
    @org.junit.jupiter.api.BeforeEach
    void setUpSelfProxy() {
        org.springframework.test.util.ReflectionTestUtils.setField(labelService, "self", labelService);
    }

    private static final Long ACTOR_ID = 100L;
    private static final Long TEAM_ID = 200L;

    // ─────────────────────────────────────────────
    // list
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("list")
    class ListTests {

        @Test
        @DisplayName("正常系: 個人スコープでは SYSTEM 既定 + 個人ラベルを sort_order 順で返す")
        void list_正常_PERSONAL() {
            TodoStatusLabelEntity sysOpen = systemDefault(1L, "未着手", TodoStatusBucket.OPEN, 0);
            TodoStatusLabelEntity sysIp = systemDefault(2L, "着手中", TodoStatusBucket.IN_PROGRESS, 1);
            TodoStatusLabelEntity sysDone = systemDefault(3L, "完了", TodoStatusBucket.COMPLETED, 2);
            TodoStatusLabelEntity personalA = personalLabel(10L, "個人ラベルA", TodoStatusBucket.IN_PROGRESS, 0);

            given(labelRepository.findAllSystemDefaults()).willReturn(List.of(sysOpen, sysIp, sysDone));
            given(labelRepository.findActiveByScope(TodoStatusLabelScope.PERSONAL, ACTOR_ID))
                    .willReturn(List.of(personalA));

            List<TodoStatusLabelResponse> result = labelService.list(
                    TodoStatusLabelScope.PERSONAL, ACTOR_ID, ACTOR_ID);

            assertThat(result).hasSize(4);
            assertThat(result.get(0).getName()).isEqualTo("未着手");
            assertThat(result.get(0).getIsSystemDefault()).isTrue();
            assertThat(result.get(3).getName()).isEqualTo("個人ラベルA");
            assertThat(result.get(3).getIsSystemDefault()).isFalse();
        }

        @Test
        @DisplayName("正常系: SYSTEM 既定3件のみを取得できる")
        void list_正常_SYSTEMラベルのみ() {
            given(labelRepository.findAllSystemDefaults()).willReturn(List.of(
                    systemDefault(1L, "未着手", TodoStatusBucket.OPEN, 0),
                    systemDefault(2L, "着手中", TodoStatusBucket.IN_PROGRESS, 1),
                    systemDefault(3L, "完了", TodoStatusBucket.COMPLETED, 2)));

            List<TodoStatusLabelResponse> result = labelService.list(
                    TodoStatusLabelScope.SYSTEM, null, ACTOR_ID);

            assertThat(result).hasSize(3);
            assertThat(result).allMatch(TodoStatusLabelResponse::getIsSystemDefault);
        }

        @Test
        @DisplayName("異常系: 個人スコープ参照で他人ID を指定すると 403")
        void list_異常_他人の個人スコープ参照不可() {
            assertThatThrownBy(() -> labelService.list(
                    TodoStatusLabelScope.PERSONAL, 999L, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }

    // ─────────────────────────────────────────────
    // create
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("正常系: 個人スコープで作成できる")
        void create_正常_PERSONAL() {
            CreateTodoStatusLabelRequest request = new CreateTodoStatusLabelRequest(
                    "レビュー中", "IN_PROGRESS", "#facc15", 5);
            given(labelRepository.existsActiveByScopeAndName(
                    TodoStatusLabelScope.PERSONAL, ACTOR_ID, "レビュー中")).willReturn(false);
            given(labelRepository.countActiveByScope(TodoStatusLabelScope.PERSONAL, ACTOR_ID)).willReturn(0L);
            given(labelRepository.save(any(TodoStatusLabelEntity.class)))
                    .willAnswer(inv -> {
                        TodoStatusLabelEntity e = inv.getArgument(0);
                        return e.toBuilder().id(99L).createdAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now()).build();
                    });

            TodoStatusLabelResponse response = labelService.create(
                    TodoStatusLabelScope.PERSONAL, ACTOR_ID, request, ACTOR_ID);

            assertThat(response.getName()).isEqualTo("レビュー中");
            assertThat(response.getBucket()).isEqualTo("IN_PROGRESS");
            assertThat(response.getIsSystemDefault()).isFalse();
            verify(auditLogService).record(eq("TODO_STATUS_LABEL_CREATED"),
                    eq(ACTOR_ID), any(), any(), any(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("異常系: SYSTEM スコープへの作成は SYSTEM_LABEL_IMMUTABLE")
        void create_異常_SYSTEM作成不可() {
            CreateTodoStatusLabelRequest request = new CreateTodoStatusLabelRequest(
                    "X", "OPEN", null, 0);
            assertThatThrownBy(() -> labelService.create(
                    TodoStatusLabelScope.SYSTEM, null, request, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TodoErrorCode.SYSTEM_LABEL_IMMUTABLE);
        }

        @Test
        @DisplayName("異常系: 同名重複は LABEL_NAME_DUPLICATED")
        void create_異常_同名重複() {
            CreateTodoStatusLabelRequest request = new CreateTodoStatusLabelRequest(
                    "レビュー中", "IN_PROGRESS", null, 0);
            given(labelRepository.existsActiveByScopeAndName(
                    TodoStatusLabelScope.PERSONAL, ACTOR_ID, "レビュー中")).willReturn(true);

            assertThatThrownBy(() -> labelService.create(
                    TodoStatusLabelScope.PERSONAL, ACTOR_ID, request, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TodoErrorCode.LABEL_NAME_DUPLICATED);
        }

        @Test
        @DisplayName("異常系: 20件上限超過は LABEL_LIMIT_EXCEEDED")
        void create_異常_上限超過() {
            CreateTodoStatusLabelRequest request = new CreateTodoStatusLabelRequest(
                    "新ラベル", "OPEN", null, 0);
            given(labelRepository.existsActiveByScopeAndName(
                    TodoStatusLabelScope.PERSONAL, ACTOR_ID, "新ラベル")).willReturn(false);
            given(labelRepository.countActiveByScope(TodoStatusLabelScope.PERSONAL, ACTOR_ID))
                    .willReturn((long) TodoStatusLabelService.MAX_LABELS_PER_SCOPE);

            assertThatThrownBy(() -> labelService.create(
                    TodoStatusLabelScope.PERSONAL, ACTOR_ID, request, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TodoErrorCode.LABEL_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("異常系: チームスコープで非ADMIN は 403（DEPUTY_ADMIN も不可）")
        void create_異常_チームADMIN権限なし() {
            CreateTodoStatusLabelRequest request = new CreateTodoStatusLabelRequest(
                    "レビュー中", "IN_PROGRESS", null, 0);
            // 設計書 §2: チームスコープ CRUD は ADMIN のみ。isAdmin が false → 403
            given(accessControlService.isAdmin(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> labelService.create(
                    TodoStatusLabelScope.TEAM, TEAM_ID, request, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }

    // ─────────────────────────────────────────────
    // update
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("正常系: ラベル名・色・バケットを更新できる")
        void update_正常() {
            TodoStatusLabelEntity existing = personalLabel(10L, "旧名", TodoStatusBucket.OPEN, 0);
            given(labelRepository.findActiveById(10L)).willReturn(Optional.of(existing));
            given(labelRepository.existsActiveByScopeAndNameExcludingId(
                    TodoStatusLabelScope.PERSONAL, ACTOR_ID, "新名", 10L)).willReturn(false);
            given(labelRepository.save(any(TodoStatusLabelEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            UpdateTodoStatusLabelRequest request = new UpdateTodoStatusLabelRequest(
                    "新名", "IN_PROGRESS", "#3b82f6", 5);

            TodoStatusLabelResponse response = labelService.update(
                    10L, TodoStatusLabelScope.PERSONAL, ACTOR_ID, request, ACTOR_ID);

            assertThat(response.getName()).isEqualTo("新名");
            assertThat(response.getBucket()).isEqualTo("IN_PROGRESS");
            assertThat(response.getColor()).isEqualTo("#3b82f6");
            assertThat(response.getSortOrder()).isEqualTo(5);
        }

        @Test
        @DisplayName("異常系: SYSTEM 既定ラベルの更新は SYSTEM_LABEL_IMMUTABLE")
        void update_異常_SYSTEM不変() {
            TodoStatusLabelEntity sys = systemDefault(1L, "未着手", TodoStatusBucket.OPEN, 0);
            given(labelRepository.findActiveById(1L)).willReturn(Optional.of(sys));

            UpdateTodoStatusLabelRequest request = new UpdateTodoStatusLabelRequest(
                    "改名", null, null, null);

            // SYSTEM ラベルは scope_type=SYSTEM/scope_id=NULL なので、SYSTEM スコープの path で来た想定
            assertThatThrownBy(() -> labelService.update(
                    1L, TodoStatusLabelScope.SYSTEM, null, request, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TodoErrorCode.SYSTEM_LABEL_IMMUTABLE);
        }

        @Test
        @DisplayName("異常系: 同名重複は LABEL_NAME_DUPLICATED")
        void update_異常_同名重複() {
            TodoStatusLabelEntity existing = personalLabel(10L, "旧名", TodoStatusBucket.OPEN, 0);
            given(labelRepository.findActiveById(10L)).willReturn(Optional.of(existing));
            given(labelRepository.existsActiveByScopeAndNameExcludingId(
                    TodoStatusLabelScope.PERSONAL, ACTOR_ID, "重複名", 10L)).willReturn(true);

            UpdateTodoStatusLabelRequest request = new UpdateTodoStatusLabelRequest(
                    "重複名", null, null, null);

            assertThatThrownBy(() -> labelService.update(
                    10L, TodoStatusLabelScope.PERSONAL, ACTOR_ID, request, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TodoErrorCode.LABEL_NAME_DUPLICATED);
        }
    }

    // ─────────────────────────────────────────────
    // delete
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("正常系: 未使用ラベルを論理削除")
        void delete_正常() {
            TodoStatusLabelEntity existing = personalLabel(10L, "削除対象", TodoStatusBucket.OPEN, 0);
            given(labelRepository.findActiveById(10L)).willReturn(Optional.of(existing));
            given(labelRepository.countTodosUsing(10L)).willReturn(0L);
            given(labelRepository.save(any(TodoStatusLabelEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            labelService.delete(10L, TodoStatusLabelScope.PERSONAL, ACTOR_ID, ACTOR_ID);

            assertThat(existing.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系: SYSTEM ラベルは削除不可")
        void delete_異常_SYSTEM削除不可() {
            TodoStatusLabelEntity sys = systemDefault(1L, "未着手", TodoStatusBucket.OPEN, 0);
            given(labelRepository.findActiveById(1L)).willReturn(Optional.of(sys));

            assertThatThrownBy(() -> labelService.delete(
                    1L, TodoStatusLabelScope.SYSTEM, null, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TodoErrorCode.SYSTEM_LABEL_IMMUTABLE);
            verify(labelRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: 使用中ラベルは LABEL_IN_USE で削除不可")
        void delete_異常_使用中() {
            TodoStatusLabelEntity existing = personalLabel(10L, "使用中", TodoStatusBucket.OPEN, 0);
            given(labelRepository.findActiveById(10L)).willReturn(Optional.of(existing));
            given(labelRepository.countTodosUsing(10L)).willReturn(3L);

            assertThatThrownBy(() -> labelService.delete(
                    10L, TodoStatusLabelScope.PERSONAL, ACTOR_ID, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TodoErrorCode.LABEL_IN_USE);
            verify(labelRepository, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────
    // validateLabelForScope
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("validateLabelForScope")
    class ValidateLabelForScopeTests {

        @Test
        @DisplayName("正常系: SYSTEM ラベルは全スコープで使用可")
        void validate_SYSTEM全スコープ可() {
            TodoStatusLabelEntity sys = systemDefault(1L, "未着手", TodoStatusBucket.OPEN, 0);
            // 例外が出なければ OK
            labelService.validateLabelForScope(sys, TodoScopeType.PERSONAL, 100L);
            labelService.validateLabelForScope(sys, TodoScopeType.TEAM, 200L);
            labelService.validateLabelForScope(sys, TodoScopeType.ORGANIZATION, 300L);
        }

        @Test
        @DisplayName("正常系: 同一スコープ・同一ID なら OK")
        void validate_同一スコープOK() {
            TodoStatusLabelEntity personal = personalLabel(10L, "X", TodoStatusBucket.OPEN, 0);
            labelService.validateLabelForScope(personal, TodoScopeType.PERSONAL, ACTOR_ID);
        }

        @Test
        @DisplayName("異常系: 他スコープのラベルは LABEL_SCOPE_MISMATCH")
        void validate_異常_他スコープ() {
            TodoStatusLabelEntity team = TodoStatusLabelEntity.builder()
                    .id(20L).scopeType(TodoStatusLabelScope.TEAM).scopeId(TEAM_ID)
                    .name("X").bucket(TodoStatusBucket.OPEN).sortOrder(0)
                    .isSystemDefault(false).createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now()).build();

            assertThatThrownBy(() -> labelService.validateLabelForScope(
                    team, TodoScopeType.PERSONAL, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TodoErrorCode.LABEL_SCOPE_MISMATCH);
        }

        @Test
        @DisplayName("異常系: 同一スコープ種別でも別 ID は LABEL_SCOPE_MISMATCH")
        void validate_異常_別ID() {
            TodoStatusLabelEntity personal = personalLabel(10L, "X", TodoStatusBucket.OPEN, 0);
            assertThatThrownBy(() -> labelService.validateLabelForScope(
                    personal, TodoScopeType.PERSONAL, 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TodoErrorCode.LABEL_SCOPE_MISMATCH);
        }
    }

    // ─────────────────────────────────────────────
    // findActiveById
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("findActiveById: 削除済みラベルは STATUS_LABEL_NOT_FOUND")
    void findActiveById_削除済みは404() {
        given(labelRepository.findActiveById(99L)).willReturn(Optional.empty());

        BusinessException ex = catchThrowableOfType(
                () -> labelService.findActiveById(99L), BusinessException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(TodoErrorCode.STATUS_LABEL_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private TodoStatusLabelEntity systemDefault(Long id, String name, TodoStatusBucket bucket, int sortOrder) {
        return TodoStatusLabelEntity.builder()
                .id(id)
                .scopeType(TodoStatusLabelScope.SYSTEM)
                .scopeId(null)
                .name(name)
                .bucket(bucket)
                .sortOrder(sortOrder)
                .isSystemDefault(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private TodoStatusLabelEntity personalLabel(Long id, String name, TodoStatusBucket bucket, int sortOrder) {
        return TodoStatusLabelEntity.builder()
                .id(id)
                .scopeType(TodoStatusLabelScope.PERSONAL)
                .scopeId(ACTOR_ID)
                .name(name)
                .bucket(bucket)
                .sortOrder(sortOrder)
                .isSystemDefault(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
