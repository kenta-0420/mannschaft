package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.dto.ActionMemoResponse;
import com.mannschaft.app.actionmemo.dto.AvailableTeamResponse;
import com.mannschaft.app.actionmemo.dto.CreateActionMemoRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyResponse;
import com.mannschaft.app.actionmemo.dto.PublishDailyToTeamRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyToTeamResponse;
import com.mannschaft.app.actionmemo.dto.PublishToTeamRequest;
import com.mannschaft.app.actionmemo.dto.PublishToTeamResponse;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoRequest;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagLinkRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.dto.TodoStatusChangeRequest;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import com.mannschaft.app.todo.service.TodoService;
import com.mannschaft.app.todo.service.TodoStatusService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;

/**
 * {@link ActionMemoService} 単体テスト — 進捗・TODO連携（バリデーション・伝播・完了・差し戻し）。
 *
 * <p>元ファイル ActionMemoServiceTest.java から分割。以下の @Nested クラスを含む:</p>
 * <ul>
 *   <li>DurationProgressValidationTest</li>
 *   <li>ProgressPropagationTest</li>
 *   <li>CompletesTodoTest</li>
 *   <li>RevertTodoCompletionTest</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoService 単体テスト — 進捗・TODO連携")
class ActionMemoServiceProgressTest {

    @Mock
    private ActionMemoRepository memoRepository;

    @Mock
    private ActionMemoTagRepository tagRepository;

    @Mock
    private ActionMemoTagLinkRepository tagLinkRepository;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private TimelinePostRepository timelinePostRepository;

    @Mock
    private ActionMemoSettingsService settingsService;

    @Mock
    private ActionMemoMetrics metrics;

    @Mock
    private TodoService todoService;

    @Mock
    private TodoStatusService todoStatusService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private ActionMemoService actionMemoService;

    @InjectMocks
    private ActionMemoPublishingService actionMemoPublishingService;

    @InjectMocks
    private ActionMemoScopeService actionMemoScopeService;

    @InjectMocks
    private ActionMemoAdminService actionMemoAdminService;

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long MEMO_ID = 1L;

    /**
     * Phase 3 テスト用: id / createdAt / Phase3 フィールドを設定済みの ActionMemoEntity を生成する。
     */
    private ActionMemoEntity phase3Memo(Long id, Long userId, LocalDate memoDate,
                                        String content, ActionMemoCategory category,
                                        Integer durationMinutes, BigDecimal progressRate,
                                        Long relatedTodoId, boolean completesTodo,
                                        LocalDateTime createdAt) {
        ActionMemoEntity memo = ActionMemoEntity.builder()
                .userId(userId)
                .memoDate(memoDate)
                .content(content)
                .category(category)
                .durationMinutes(durationMinutes)
                .progressRate(progressRate)
                .relatedTodoId(relatedTodoId)
                .completesTodo(completesTodo)
                .build();
        ReflectionTestUtils.setField(memo, "id", id);
        if (createdAt != null) {
            ReflectionTestUtils.setField(memo, "createdAt", createdAt);
        }
        return memo;
    }

    /**
     * Phase 3 テスト用: 自分所有の PERSONAL TODO を生成する。
     */
    private TodoEntity ownPersonalTodo(Long id, Long userId, String title, TodoStatus status) {
        TodoEntity todo = TodoEntity.builder()
                .scopeType(TodoScopeType.PERSONAL)
                .scopeId(userId)
                .title(title)
                .status(status)
                .priority(TodoPriority.MEDIUM)
                .createdBy(userId)
                .sortOrder(0)
                .build();
        ReflectionTestUtils.setField(todo, "id", id);
        return todo;
    }

    // ------------------------------------------------------------------
    // DurationProgressValidationTest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 3: duration_minutes / progress_rate バリデーション")
    class DurationProgressValidationTest {

        @Test
        @DisplayName("duration_minutes = 0（境界値）は Service 層で通る")
        void createMemo_durationZero_passes() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setDurationMinutes(0);

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ActionMemoResponse response = actionMemoService.createMemo(req, USER_ID);
            assertThat(response.getDurationMinutes()).isEqualTo(0);
        }

        @Test
        @DisplayName("duration_minutes = 1440（境界値）は Service 層で通る")
        void createMemo_duration1440_passes() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setDurationMinutes(1440);

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ActionMemoResponse response = actionMemoService.createMemo(req, USER_ID);
            assertThat(response.getDurationMinutes()).isEqualTo(1440);
        }

        @Test
        @Disabled("実装ギャップ: duration の範囲チェックは Bean Validation (@Min/@Max) のみで Service 層には無い。"
                + "設計書 §10.1 の「createMemo_durationOutOfRange_throws_INVALID_DURATION」は "
                + "Service 単体テストでは検証不能（Controller 層の WebMvcTest が適切）。"
                + "実装を Service 層にも複線するか別途検討。")
        @DisplayName("[実装ギャップ] duration_minutes = -1 で INVALID_DURATION を投げてほしい")
        void createMemo_durationNegative_throws_INVALID_DURATION() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setDurationMinutes(-1);

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);

            assertThatThrownBy(() -> actionMemoService.createMemo(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_INVALID_DURATION);
        }

        @Test
        @Disabled("実装ギャップ: duration の範囲チェックは Bean Validation (@Min/@Max) のみで Service 層には無い。"
                + "1441 も同上。")
        @DisplayName("[実装ギャップ] duration_minutes = 1441 で INVALID_DURATION を投げてほしい")
        void createMemo_durationOver1440_throws_INVALID_DURATION() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setDurationMinutes(1441);

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);

            assertThatThrownBy(() -> actionMemoService.createMemo(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_INVALID_DURATION);
        }

        @Test
        @Disabled("実装ギャップ: progress_rate の範囲チェックも Bean Validation (@DecimalMin/@DecimalMax) のみで "
                + "Service 層には無い。設計書 §10.1 の「INVALID_PROGRESS_RATE」を投げる経路を Service に追加するか別途検討。")
        @DisplayName("[実装ギャップ] progress_rate = 100.01 で INVALID_PROGRESS_RATE を投げてほしい")
        void createMemo_progressRateOutOfRange_throws_INVALID_PROGRESS_RATE() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setRelatedTodoId(42L);
            req.setProgressRate(new BigDecimal("100.01"));

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);
            given(todoRepository.findByIdAndDeletedAtIsNull(42L))
                    .willReturn(Optional.of(ownPersonalTodo(42L, USER_ID, "TODO", TodoStatus.OPEN)));

            assertThatThrownBy(() -> actionMemoService.createMemo(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_INVALID_PROGRESS_RATE);
        }

        @Test
        @DisplayName("progress_rate 指定 + relatedTodoId 未指定 → PROGRESS_REQUIRES_TODO")
        void createMemo_progressRateWithoutTodo_throws_PROGRESS_REQUIRES_TODO() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setProgressRate(new BigDecimal("50.00"));
            // relatedTodoId 未指定

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);

            assertThatThrownBy(() -> actionMemoService.createMemo(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_PROGRESS_REQUIRES_TODO);
        }
    }

    // ------------------------------------------------------------------
    // ProgressPropagationTest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 3: progress_rate の TODO への伝播")
    class ProgressPropagationTest {

        @Test
        @DisplayName("createMemo: progress_rate 指定で TodoService.setProgressRate が呼ばれる")
        void createMemo_withProgressRate_callsTodoSetProgressRate() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setRelatedTodoId(42L);
            req.setProgressRate(new BigDecimal("70.00"));

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);
            given(todoRepository.findByIdAndDeletedAtIsNull(42L))
                    .willReturn(Optional.of(ownPersonalTodo(42L, USER_ID, "TODO", TodoStatus.OPEN)));
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            actionMemoService.createMemo(req, USER_ID);

            verify(todoService).setProgressRate(eq(42L), eq(new BigDecimal("70.00")));
        }

        @Test
        @DisplayName("updateMemo: progress_rate 変更時に TODO に伝播する")
        void updateMemo_progressRateChanged_propagates() {
            // 既存メモ（relatedTodoId 設定済み）
            ActionMemoEntity existing = phase3Memo(MEMO_ID, USER_ID, LocalDate.now(),
                    "既存", ActionMemoCategory.WORK, null, null, 42L, false,
                    LocalDateTime.now());
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(existing));
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(tagLinkRepository.findByMemoId(any())).willReturn(List.of());

            UpdateActionMemoRequest req = new UpdateActionMemoRequest();
            req.setProgressRate(new BigDecimal("85.00"));

            actionMemoService.updateMemo(MEMO_ID, req, USER_ID);

            verify(todoService).setProgressRate(eq(42L), eq(new BigDecimal("85.00")));
        }
    }

    // ------------------------------------------------------------------
    // CompletesTodoTest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 3: completes_todo による TODO 完了同期")
    class CompletesTodoTest {

        @Test
        @DisplayName("completes_todo = true で TodoService.changeStatus が COMPLETED で呼ばれる")
        void createMemo_completesTodoTrue_callsTodoServiceChangeStatusCompleted() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("作業完了");
            req.setRelatedTodoId(42L);
            req.setCompletesTodo(true);

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);
            // validateTodoScope の OPEN な TODO
            TodoEntity openTodo = ownPersonalTodo(42L, USER_ID, "作業", TodoStatus.OPEN);
            given(todoRepository.findByIdAndDeletedAtIsNull(42L))
                    .willReturn(Optional.of(openTodo));
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> {
                        ActionMemoEntity saved = inv.getArgument(0);
                        if (saved.getId() == null) {
                            ReflectionTestUtils.setField(saved, "id", MEMO_ID);
                        }
                        return saved;
                    });

            actionMemoService.createMemo(req, USER_ID);

            ArgumentCaptor<TodoStatusChangeRequest> captor =
                    ArgumentCaptor.forClass(TodoStatusChangeRequest.class);
            verify(todoStatusService).changeStatus(eq(42L), captor.capture(), eq(USER_ID));
            assertThat(captor.getValue().getStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("completes_todo = true でも既に COMPLETED なら changeStatus を呼ばない")
        void createMemo_completesTodoButTodoAlreadyCompleted_skipsChange() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setRelatedTodoId(42L);
            req.setCompletesTodo(true);

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);
            TodoEntity completedTodo = ownPersonalTodo(42L, USER_ID, "作業", TodoStatus.COMPLETED);
            given(todoRepository.findByIdAndDeletedAtIsNull(42L))
                    .willReturn(Optional.of(completedTodo));
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            actionMemoService.createMemo(req, USER_ID);

            verify(todoStatusService, never()).changeStatus(any(), any(), any());
        }

        @Test
        @DisplayName("completes_todo = true + relatedTodoId 未指定 → COMPLETES_REQUIRES_TODO")
        void createMemo_completesTodoTrueWithoutTodo_throws_COMPLETES_REQUIRES_TODO() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setCompletesTodo(true);
            // relatedTodoId 未指定

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);

            assertThatThrownBy(() -> actionMemoService.createMemo(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_COMPLETES_REQUIRES_TODO);
        }

        @Test
        @DisplayName("completeTodoFromMemo: AuditLogService.record の metadata JSON に "
                + "source=ACTION_MEMO / source_id=memoId が含まれる")
        void completeTodoFromMemo_recordsAuditLogWithSourceActionMemo() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("作業完了");
            req.setRelatedTodoId(42L);
            req.setCompletesTodo(true);

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);
            TodoEntity openTodo = ownPersonalTodo(42L, USER_ID, "作業", TodoStatus.OPEN);
            given(todoRepository.findByIdAndDeletedAtIsNull(42L))
                    .willReturn(Optional.of(openTodo));
            // memo.save は id をセットして返す
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> {
                        ActionMemoEntity saved = inv.getArgument(0);
                        ReflectionTestUtils.setField(saved, "id", 7777L);
                        return saved;
                    });

            actionMemoService.createMemo(req, USER_ID);

            // AuditLogService.record(eventType, userId, targetUserId, teamId, organizationId,
            //                        ipAddress, userAgent, sessionHash, metadata)
            ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditLogService).record(
                    eq("AUDIT_LOG_TODO_STATUS_CHANGED"),
                    eq(USER_ID),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    metadataCaptor.capture()
            );
            String metadata = metadataCaptor.getValue();
            assertThat(metadata)
                    .contains("\"source\":\"ACTION_MEMO\"")
                    .contains("\"source_id\":7777")
                    .contains("\"todo_id\":42");
        }
    }

    // ------------------------------------------------------------------
    // RevertTodoCompletionTest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 4-β: revertTodoCompletion（TODO 差し戻し）")
    class RevertTodoCompletionTest {

        private static final Long ADMIN_ID = 200L;
        private static final Long TEAM_ID = 300L;

        private ActionMemoEntity memoWithTodo(Long memoId, Long userId, Long todoId, Long postedTeamId, boolean completesTodo) {
            ActionMemoEntity memo = ActionMemoEntity.builder()
                    .userId(userId)
                    .memoDate(LocalDate.now())
                    .content("テストメモ")
                    .postedTeamId(postedTeamId)
                    .relatedTodoId(todoId)
                    .completesTodo(completesTodo)
                    .category(ActionMemoCategory.WORK)
                    .build();
            ReflectionTestUtils.setField(memo, "id", memoId);
            return memo;
        }

        private TodoEntity todoWith(Long todoId, TodoStatus status) {
            TodoEntity todo = TodoEntity.builder()
                    .scopeType(TodoScopeType.PERSONAL)
                    .scopeId(USER_ID)
                    .title("テストTODO")
                    .status(status)
                    .build();
            ReflectionTestUtils.setField(todo, "id", todoId);
            return todo;
        }

        @Test
        @DisplayName("ADMIN が差し戻し → TODO が OPEN に戻る")
        void revertTodoCompletion_asAdmin_success() {
            Long todoId = 50L;
            ActionMemoEntity memo = memoWithTodo(MEMO_ID, USER_ID, todoId, TEAM_ID, true);
            TodoEntity todo = todoWith(todoId, TodoStatus.COMPLETED);

            given(memoRepository.findById(MEMO_ID)).willReturn(Optional.of(memo));
            given(userRoleRepository.countTeamAdminByUserIdAndTeamId(ADMIN_ID, TEAM_ID)).willReturn(1L);
            given(todoRepository.findByIdAndDeletedAtIsNull(todoId)).willReturn(Optional.of(todo));

            actionMemoAdminService.revertTodoCompletion(MEMO_ID, ADMIN_ID);

            verify(todoStatusService).changeStatus(eq(todoId), any(TodoStatusChangeRequest.class), eq(USER_ID));
            verify(auditLogService).record(
                    eq("AUDIT_LOG_TODO_REVERTED_BY_ADMIN"),
                    eq(ADMIN_ID),
                    any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("ADMIN 以外が差し戻し → TODO_REVERT_NOT_ALLOWED")
        void revertTodoCompletion_notAdmin_forbidden() {
            Long todoId = 50L;
            ActionMemoEntity memo = memoWithTodo(MEMO_ID, USER_ID, todoId, TEAM_ID, true);

            given(memoRepository.findById(MEMO_ID)).willReturn(Optional.of(memo));
            given(userRoleRepository.countTeamAdminByUserIdAndTeamId(USER_ID, TEAM_ID)).willReturn(0L);

            assertThatThrownBy(() -> actionMemoAdminService.revertTodoCompletion(MEMO_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_TODO_REVERT_NOT_ALLOWED);
        }

        @Test
        @DisplayName("completesTodo=false のメモは差し戻し不可 → TODO_NOT_COMPLETED_BY_MEMO")
        void revertTodoCompletion_completesTodoFalse_throws() {
            ActionMemoEntity memo = memoWithTodo(MEMO_ID, USER_ID, 50L, TEAM_ID, false);

            given(memoRepository.findById(MEMO_ID)).willReturn(Optional.of(memo));
            // 認可判定を業務状態の検証より前に行うため、ADMIN であることを明示 stub する。
            // 未 stub だと Mockito 既定の 0L により認可で先に弾かれ、
            // 本テストが検証したい業務エラー（TODO_NOT_COMPLETED_BY_MEMO）に到達しない。
            given(userRoleRepository.countTeamAdminByUserIdAndTeamId(ADMIN_ID, TEAM_ID)).willReturn(1L);

            assertThatThrownBy(() -> actionMemoAdminService.revertTodoCompletion(MEMO_ID, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_TODO_NOT_COMPLETED_BY_MEMO);
        }

        @Test
        @DisplayName("チーム未投稿メモの差し戻しは権限判定で拒否される（メモの状態は開示しない）")
        void revertTodoCompletion_notPostedToTeam_forbidden() {
            // postedTeamId = null（チーム未投稿）かつ completesTodo = false。
            // 認可を業務検証より前に置いているため、状態差ではなく一律 403 相当となる。
            ActionMemoEntity memo = memoWithTodo(MEMO_ID, USER_ID, 50L, null, false);

            given(memoRepository.findById(MEMO_ID)).willReturn(Optional.of(memo));

            assertThatThrownBy(() -> actionMemoAdminService.revertTodoCompletion(MEMO_ID, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_TODO_REVERT_NOT_ALLOWED);
        }
    }
}
