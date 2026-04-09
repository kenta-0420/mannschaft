package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.dto.ActionMemoResponse;
import com.mannschaft.app.actionmemo.dto.CreateActionMemoRequest;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoRequest;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagLinkRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link ActionMemoService} 単体テスト。
 *
 * <p>設計書 §7.1 の Phase 1 スコープ 13 項目のうち本ファイルで以下を検証する:</p>
 * <ul>
 *   <li>createMemo 正常系 — content のみで作成成功、memo_date が JST 今日に自動セット</li>
 *   <li>mood silent ignore — mood_enabled=false のユーザーが mood 送信 → NULL 化、400 を返さない</li>
 *   <li>1日 200 件上限 — 200 件まで成功、201 件目で 400</li>
 *   <li>未来日付 — memo_date=翌日で 400</li>
 *   <li>スコープ違反 TODO 紐付け — 他人の TODO → 404（ACTION_MEMO_TODO_NOT_FOUND）</li>
 *   <li>find/get/update/delete IDOR — 他人の memoId で 404</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoService 単体テスト")
class ActionMemoServiceTest {

    @Mock
    private ActionMemoRepository memoRepository;

    @Mock
    private ActionMemoTagRepository tagRepository;

    @Mock
    private ActionMemoTagLinkRepository tagLinkRepository;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private ActionMemoSettingsService settingsService;

    @Mock
    private ActionMemoMetrics metrics;

    @InjectMocks
    private ActionMemoService actionMemoService;

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long MEMO_ID = 1L;

    private ActionMemoEntity savedMemo(Long id, Long userId, LocalDate memoDate,
                                        String content, ActionMemoMood mood) {
        return ActionMemoEntity.builder()
                .userId(userId)
                .memoDate(memoDate)
                .content(content)
                .mood(mood)
                .build();
    }

    // ==================================================================
    // createMemo
    // ==================================================================

    @Nested
    @DisplayName("createMemo")
    class CreateMemoTest {

        @Test
        @DisplayName("正常系: content のみで作成成功、memo_date が自動セットされる")
        void create_successWithContentOnly() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("朝 30分 散歩した");

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ActionMemoResponse response = actionMemoService.createMemo(req, USER_ID);

            assertThat(response).isNotNull();
            assertThat(response.getContent()).isEqualTo("朝 30分 散歩した");
            assertThat(response.getMemoDate()).isNotNull();
        }

        @Test
        @DisplayName("mood silent ignore: mood_enabled=false なら送信された mood は NULL 化")
        void create_moodSilentlyIgnoredWhenDisabled() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setMood(ActionMemoMood.GOOD);

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);
            given(settingsService.getMoodEnabled(USER_ID)).willReturn(false);
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ActionMemoResponse response = actionMemoService.createMemo(req, USER_ID);

            assertThat(response.getMood()).isNull();
        }

        @Test
        @DisplayName("mood_enabled=true なら送信された mood が反映される")
        void create_moodAcceptedWhenEnabled() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setMood(ActionMemoMood.GREAT);

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);
            given(settingsService.getMoodEnabled(USER_ID)).willReturn(true);
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ActionMemoResponse response = actionMemoService.createMemo(req, USER_ID);

            assertThat(response.getMood()).isEqualTo(ActionMemoMood.GREAT);
        }

        @Test
        @DisplayName("1日 200 件上限: 201 件目で 400（DAILY_LIMIT_EXCEEDED）")
        void create_failsWhenDailyLimitReached() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(200L);

            assertThatThrownBy(() -> actionMemoService.createMemo(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_DAILY_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("未来日付: memo_date=翌日で 400（FUTURE_DATE）")
        void create_failsWithFutureDate() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setMemoDate(LocalDate.now().plusDays(1));

            assertThatThrownBy(() -> actionMemoService.createMemo(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_FUTURE_DATE);
        }

        @Test
        @DisplayName("スコープ違反 TODO 紐付け: 他人の TODO → 404（TODO_NOT_FOUND）")
        void create_failsWithOtherUsersTodo() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setRelatedTodoId(42L);

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);

            // 他人所有の PERSONAL TODO
            TodoEntity otherUsersTodo = TodoEntity.builder()
                    .scopeType(TodoScopeType.PERSONAL)
                    .scopeId(OTHER_USER_ID)
                    .title("他人のタスク")
                    .status(TodoStatus.OPEN)
                    .priority(TodoPriority.MEDIUM)
                    .createdBy(OTHER_USER_ID)
                    .sortOrder(0)
                    .build();
            given(todoRepository.findByIdAndDeletedAtIsNull(42L))
                    .willReturn(Optional.of(otherUsersTodo));

            assertThatThrownBy(() -> actionMemoService.createMemo(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_TODO_NOT_FOUND);
        }

        @Test
        @DisplayName("スコープ違反 TODO 紐付け: 自分の TEAM TODO も 404")
        void create_failsWithOwnTeamTodo() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            req.setRelatedTodoId(42L);

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);

            TodoEntity teamTodo = TodoEntity.builder()
                    .scopeType(TodoScopeType.TEAM)
                    .scopeId(500L)
                    .title("チームタスク")
                    .status(TodoStatus.OPEN)
                    .priority(TodoPriority.MEDIUM)
                    .createdBy(USER_ID)
                    .sortOrder(0)
                    .build();
            given(todoRepository.findByIdAndDeletedAtIsNull(42L))
                    .willReturn(Optional.of(teamTodo));

            assertThatThrownBy(() -> actionMemoService.createMemo(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_TODO_NOT_FOUND);
        }
    }

    // ==================================================================
    // getMemo / updateMemo / deleteMemo - IDOR
    // ==================================================================

    @Nested
    @DisplayName("IDOR 対策（404）")
    class IdorTest {

        @Test
        @DisplayName("getMemo: 他人の memoId → 404（ACTION_MEMO_NOT_FOUND）")
        void getMemo_othersId_returns404() {
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> actionMemoService.getMemo(MEMO_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_NOT_FOUND);
        }

        @Test
        @DisplayName("updateMemo: 他人の memoId → 404")
        void updateMemo_othersId_returns404() {
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.empty());

            UpdateActionMemoRequest req = new UpdateActionMemoRequest();
            req.setContent("更新");

            assertThatThrownBy(() -> actionMemoService.updateMemo(MEMO_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_NOT_FOUND);
        }

        @Test
        @DisplayName("deleteMemo: 他人の memoId → 404")
        void deleteMemo_othersId_returns404() {
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> actionMemoService.deleteMemo(MEMO_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_NOT_FOUND);
        }

        @Test
        @DisplayName("updateMemo: 自分のメモなら正常に更新できる")
        void updateMemo_ownMemo_success() {
            ActionMemoEntity existing = savedMemo(MEMO_ID, USER_ID, LocalDate.now(),
                    "既存", null);
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(existing));
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(tagLinkRepository.findByMemoId(any())).willReturn(List.of());

            UpdateActionMemoRequest req = new UpdateActionMemoRequest();
            req.setContent("更新済み");

            ActionMemoResponse response = actionMemoService.updateMemo(MEMO_ID, req, USER_ID);
            assertThat(response.getContent()).isEqualTo("更新済み");
        }
    }
}
