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
 * {@link ActionMemoService} 単体テスト — コア機能（作成・IDOR・カテゴリデフォルト）。
 *
 * <p>元ファイル ActionMemoServiceTest.java から分割。以下の @Nested クラスを含む:</p>
 * <ul>
 *   <li>CreateMemoTest</li>
 *   <li>IdorTest</li>
 *   <li>CategoryDefaultTest</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoService 単体テスト — コア機能")
class ActionMemoServiceCoreTest {

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

    private ActionMemoEntity savedMemo(Long id, Long userId, LocalDate memoDate,
                                        String content, ActionMemoMood mood) {
        return ActionMemoEntity.builder()
                .userId(userId)
                .memoDate(memoDate)
                .content(content)
                .mood(mood)
                .build();
    }

    /**
     * Phase 3 テスト用: ユーザー設定エンティティを生成する。
     */
    private UserActionMemoSettingsEntity settingsOf(Long userId,
                                                    ActionMemoCategory defaultCategory,
                                                    Long defaultPostTeamId) {
        return UserActionMemoSettingsEntity.builder()
                .userId(userId)
                .moodEnabled(false)
                .defaultCategory(defaultCategory)
                .defaultPostTeamId(defaultPostTeamId)
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

    // ------------------------------------------------------------------
    // CategoryDefaultTest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 3: カテゴリのデフォルト適用")
    class CategoryDefaultTest {

        @Test
        @DisplayName("category 省略 → settings.defaultCategory（WORK）が適用される")
        void createMemo_categoryOmitted_appliesDefaultFromSettings() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            // category 未指定

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);
            given(settingsService.findSettings(USER_ID))
                    .willReturn(Optional.of(settingsOf(USER_ID, ActionMemoCategory.WORK, null)));
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ActionMemoResponse response = actionMemoService.createMemo(req, USER_ID);

            assertThat(response.getCategory()).isEqualTo(ActionMemoCategory.WORK);
        }

        @Test
        @DisplayName("category 省略 + settings なし → PRIVATE が適用される")
        void createMemo_categoryOmittedNoSettings_defaultsToPrivate() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");
            // category 未指定

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);
            given(settingsService.findSettings(USER_ID)).willReturn(Optional.empty());
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ActionMemoResponse response = actionMemoService.createMemo(req, USER_ID);

            assertThat(response.getCategory()).isEqualTo(ActionMemoCategory.PRIVATE);
        }

        @Test
        @DisplayName("category 省略 + settings.defaultCategory が NULL → PRIVATE が適用される")
        void createMemo_categoryOmittedSettingsHasNullCategory_defaultsToPrivate() {
            CreateActionMemoRequest req = new CreateActionMemoRequest();
            req.setContent("メモ");

            given(memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(eq(USER_ID), any()))
                    .willReturn(0L);
            given(settingsService.findSettings(USER_ID))
                    .willReturn(Optional.of(settingsOf(USER_ID, null, null)));
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ActionMemoResponse response = actionMemoService.createMemo(req, USER_ID);

            assertThat(response.getCategory()).isEqualTo(ActionMemoCategory.PRIVATE);
        }
    }
}
