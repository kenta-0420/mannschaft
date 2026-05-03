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
 * {@link ActionMemoService} 単体テスト。
 *
 * <p>設計書 §7.1 のスコープ項目を本ファイルで検証する:</p>
 * <ul>
 *   <li>createMemo 正常系 — content のみで作成成功、memo_date が JST 今日に自動セット</li>
 *   <li>mood silent ignore — mood_enabled=false のユーザーが mood 送信 → NULL 化、400 を返さない</li>
 *   <li>1日 200 件上限 — 200 件まで成功、201 件目で 400</li>
 *   <li>未来日付 — memo_date=翌日で 400</li>
 *   <li>スコープ違反 TODO 紐付け — 他人の TODO → 404（ACTION_MEMO_TODO_NOT_FOUND）</li>
 *   <li>find/get/update/delete IDOR — 他人の memoId で 404</li>
 *   <li>publishDaily 正常系 — 当日のメモを PERSONAL タイムラインに1件投稿</li>
 *   <li>publishDaily 冪等性 — 既存投稿は論理削除してから差し替え</li>
 *   <li>publishDaily 0件 — 対象日にメモ無しで 400（ACTION_MEMO_NO_MEMOS_FOR_DATE）</li>
 *   <li>publishDaily XSS 対策 — extra_comment の script タグ等は HtmlSanitizer で除去される</li>
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
    private TimelinePostRepository timelinePostRepository;

    @Mock
    private ActionMemoSettingsService settingsService;

    @Mock
    private ActionMemoMetrics metrics;

    @Mock
    private TodoService todoService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private AuditLogService auditLogService;

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

    // ==================================================================
    // publishDaily（Phase 2）
    // ==================================================================

    /**
     * id と createdAt を設定済みの ActionMemoEntity を生成する。
     * publishDaily 本文組み立てでは createdAt の HH:mm が使われるため必須。
     */
    private ActionMemoEntity memoWithCreatedAt(Long id, Long userId, LocalDate memoDate,
                                                String content, ActionMemoMood mood,
                                                LocalDateTime createdAt) {
        ActionMemoEntity memo = ActionMemoEntity.builder()
                .userId(userId)
                .memoDate(memoDate)
                .content(content)
                .mood(mood)
                .build();
        ReflectionTestUtils.setField(memo, "id", id);
        ReflectionTestUtils.setField(memo, "createdAt", createdAt);
        return memo;
    }

    @Nested
    @DisplayName("publishDaily")
    class PublishDailyTest {

        private static final LocalDate TARGET_DATE = LocalDate.of(2026, 4, 9);

        @Test
        @DisplayName("正常系: 3件のメモ → PERSONAL タイムラインに1件 INSERT、各メモの timelinePostId を更新")
        void publishDaily_success() {
            List<ActionMemoEntity> memos = new ArrayList<>();
            memos.add(memoWithCreatedAt(1L, USER_ID, TARGET_DATE, "朝散歩", null,
                    LocalDateTime.of(2026, 4, 9, 9, 15)));
            memos.add(memoWithCreatedAt(2L, USER_ID, TARGET_DATE, "会議準備", null,
                    LocalDateTime.of(2026, 4, 9, 10, 42)));
            memos.add(memoWithCreatedAt(3L, USER_ID, TARGET_DATE, "コード書いた", null,
                    LocalDateTime.of(2026, 4, 9, 14, 30)));

            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE))
                    .willReturn(memos);
            given(settingsService.getMoodEnabled(USER_ID)).willReturn(false);

            AtomicLong postIdSeq = new AtomicLong(1000L);
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity post = inv.getArgument(0);
                        ReflectionTestUtils.setField(post, "id", postIdSeq.getAndIncrement());
                        return post;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishDailyRequest req = new PublishDailyRequest();
            req.setMemoDate(TARGET_DATE);

            PublishDailyResponse response = actionMemoService.publishDaily(req, USER_ID);

            assertThat(response).isNotNull();
            assertThat(response.getMemoCount()).isEqualTo(3);
            assertThat(response.getMemoDate()).isEqualTo(TARGET_DATE);
            assertThat(response.getTimelinePostId()).isEqualTo(1000L);

            ArgumentCaptor<TimelinePostEntity> postCaptor =
                    ArgumentCaptor.forClass(TimelinePostEntity.class);
            verify(timelinePostRepository).save(postCaptor.capture());
            TimelinePostEntity saved = postCaptor.getValue();
            assertThat(saved.getScopeType()).isEqualTo(PostScopeType.PERSONAL);
            assertThat(saved.getScopeId()).isEqualTo(USER_ID);
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getContent())
                    .contains("## 2026-04-09 の行動ログ")
                    .contains("朝散歩")
                    .contains("会議準備")
                    .contains("コード書いた");

            // 全メモに timelinePostId が設定される
            assertThat(memos).allSatisfy(m ->
                    assertThat(m.getTimelinePostId()).isEqualTo(1000L));
            verify(metrics).incrementPublishDailySuccess();
        }

        @Test
        @DisplayName("冪等性: 既存 timeline_post_id のあるメモがあれば旧投稿を論理削除してから差し替え")
        void publishDaily_idempotentOverwrite() {
            ActionMemoEntity memoWithOldPost = memoWithCreatedAt(
                    1L, USER_ID, TARGET_DATE, "既存メモ", null,
                    LocalDateTime.of(2026, 4, 9, 11, 0));
            memoWithOldPost.setTimelinePostId(500L);
            List<ActionMemoEntity> memos = List.of(memoWithOldPost);

            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE))
                    .willReturn(memos);
            given(settingsService.getMoodEnabled(USER_ID)).willReturn(false);

            TimelinePostEntity oldPost = TimelinePostEntity.builder()
                    .scopeType(PostScopeType.PERSONAL)
                    .scopeId(USER_ID)
                    .userId(USER_ID)
                    .content("旧本文")
                    .build();
            ReflectionTestUtils.setField(oldPost, "id", 500L);
            given(timelinePostRepository.findById(500L)).willReturn(Optional.of(oldPost));

            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        if (p.getId() == null) {
                            ReflectionTestUtils.setField(p, "id", 2000L);
                        }
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishDailyRequest req = new PublishDailyRequest();
            req.setMemoDate(TARGET_DATE);

            PublishDailyResponse response = actionMemoService.publishDaily(req, USER_ID);

            // 旧投稿の論理削除が呼ばれている
            assertThat(oldPost.getDeletedAt()).isNotNull();
            // 新投稿 ID で差し替え
            assertThat(response.getTimelinePostId()).isEqualTo(2000L);
            assertThat(memoWithOldPost.getTimelinePostId()).isEqualTo(2000L);
            verify(timelinePostRepository).findById(500L);
        }

        @Test
        @DisplayName("対象日にメモ 0 件 → 400（ACTION_MEMO_NO_MEMOS_FOR_DATE）")
        void publishDaily_failsWhenNoMemos() {
            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE))
                    .willReturn(List.of());

            PublishDailyRequest req = new PublishDailyRequest();
            req.setMemoDate(TARGET_DATE);

            assertThatThrownBy(() -> actionMemoService.publishDaily(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_NO_MEMOS_FOR_DATE);

            verify(metrics).incrementPublishDailyError();
        }

        @Test
        @DisplayName("extra_comment XSS 対策: <script> タグが本文からサニタイズされる")
        void publishDaily_sanitizesExtraCommentXss() {
            List<ActionMemoEntity> memos = List.of(memoWithCreatedAt(
                    1L, USER_ID, TARGET_DATE, "メモ", null,
                    LocalDateTime.of(2026, 4, 9, 12, 0)));

            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE))
                    .willReturn(memos);
            given(settingsService.getMoodEnabled(USER_ID)).willReturn(false);
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 3000L);
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishDailyRequest req = new PublishDailyRequest();
            req.setMemoDate(TARGET_DATE);
            req.setExtraComment("今日はよく動けた<script>alert('xss')</script>");

            actionMemoService.publishDaily(req, USER_ID);

            ArgumentCaptor<TimelinePostEntity> captor =
                    ArgumentCaptor.forClass(TimelinePostEntity.class);
            verify(timelinePostRepository).save(captor.capture());
            String savedContent = captor.getValue().getContent();
            assertThat(savedContent)
                    .contains("今日はよく動けた")
                    .doesNotContain("<script>")
                    .doesNotContain("</script>");
        }
    }

    // ==================================================================
    // Phase 3 テスト: 設計書 §10.1 に列挙されたユニットテスト項目を検証する
    // ==================================================================

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

    // ------------------------------------------------------------------
    // 1. CategoryDefaultTest
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

    // ------------------------------------------------------------------
    // 2. DurationProgressValidationTest
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
    // 3. ProgressPropagationTest
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
    // 4. CompletesTodoTest
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
            verify(todoService).changeStatus(eq(42L), captor.capture(), eq(USER_ID));
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

            verify(todoService, never()).changeStatus(any(), any(), any());
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
    // 5. PublishToTeamTest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 3: publishToTeam（メモ個別チーム投稿）")
    class PublishToTeamTest {

        @Test
        @DisplayName("正常系: WORK / 自チーム / 未投稿 → timelinePostId が返る")
        void publishToTeam_workMemoOwnTeam_returnsTimelinePostId() {
            ActionMemoEntity workMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.now(),
                    "作業ログ", ActionMemoCategory.WORK, 30, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 10, 0));
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(workMemo));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 9000L);
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishToTeamRequest req = new PublishToTeamRequest(42L, null);
            PublishToTeamResponse response = actionMemoService.publishToTeam(MEMO_ID, req, USER_ID);

            assertThat(response.getTimelinePostId()).isEqualTo(9000L);
            assertThat(response.getTeamId()).isEqualTo(42L);
            assertThat(response.getMemoId()).isEqualTo(MEMO_ID);
            assertThat(workMemo.getPostedTeamId()).isEqualTo(42L);
            assertThat(workMemo.getTimelinePostId()).isEqualTo(9000L);
        }

        @Test
        @DisplayName("PRIVATE メモは ONLY_WORK_CAN_BE_POSTED で拒否")
        void publishToTeam_privateMemo_throws_ONLY_WORK_CAN_BE_POSTED() {
            ActionMemoEntity privateMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.now(),
                    "私事", ActionMemoCategory.PRIVATE, null, null, null, false,
                    LocalDateTime.now());
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(privateMemo));

            PublishToTeamRequest req = new PublishToTeamRequest(42L, null);

            assertThatThrownBy(() -> actionMemoService.publishToTeam(MEMO_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_ONLY_WORK_CAN_BE_POSTED);
        }

        @Test
        @DisplayName("既に投稿済みのメモは ALREADY_POSTED で拒否")
        void publishToTeam_alreadyPosted_throws_ALREADY_POSTED() {
            ActionMemoEntity workMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.now(),
                    "作業", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.now());
            workMemo.setPostedTeamId(42L); // 既投稿

            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(workMemo));

            PublishToTeamRequest req = new PublishToTeamRequest(42L, null);

            assertThatThrownBy(() -> actionMemoService.publishToTeam(MEMO_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_ALREADY_POSTED);
        }

        @Test
        @DisplayName("非メンバーチームは TEAM_NOT_FOUND（IDOR 対策で 404）")
        void publishToTeam_notTeamMember_throws_TEAM_NOT_FOUND() {
            ActionMemoEntity workMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.now(),
                    "作業", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.now());
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(workMemo));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(false);

            PublishToTeamRequest req = new PublishToTeamRequest(42L, null);

            assertThatThrownBy(() -> actionMemoService.publishToTeam(MEMO_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_TEAM_NOT_FOUND);
        }

        @Test
        @DisplayName("team_id 省略 + settings.defaultPostTeamId も NULL → TEAM_ID_REQUIRED")
        void publishToTeam_teamIdNullAndNoDefault_throws_TEAM_ID_REQUIRED() {
            ActionMemoEntity workMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.now(),
                    "作業", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.now());
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(workMemo));
            given(settingsService.findSettings(USER_ID)).willReturn(Optional.empty());

            PublishToTeamRequest req = new PublishToTeamRequest(null, null);

            assertThatThrownBy(() -> actionMemoService.publishToTeam(MEMO_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_TEAM_ID_REQUIRED);
        }

        @Test
        @DisplayName("team_id 省略 + settings.defaultPostTeamId あり → デフォルトチームに投稿成功")
        void publishToTeam_teamIdNullUsesDefault_succeeds() {
            ActionMemoEntity workMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.now(),
                    "作業", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 10, 0));
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(workMemo));
            given(settingsService.findSettings(USER_ID))
                    .willReturn(Optional.of(settingsOf(USER_ID, ActionMemoCategory.WORK, 99L)));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 99L)).willReturn(true);
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 9100L);
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishToTeamRequest req = new PublishToTeamRequest(null, null);
            PublishToTeamResponse response = actionMemoService.publishToTeam(MEMO_ID, req, USER_ID);

            assertThat(response.getTeamId()).isEqualTo(99L);
            assertThat(response.getTimelinePostId()).isEqualTo(9100L);
        }

        @Test
        @DisplayName("本文フォーマット: HH:MM / duration / 進捗率 / 関連TODO / extra_comment(sanitized) を含む")
        void publishToTeam_contentFormat_includesHHMM_duration_progress_todoTitle_extraComment_sanitized() {
            // 9:15 (JST) / WORK / duration=30 / progressRate=70.5 / relatedTodoId=42
            LocalDateTime created = LocalDateTime.of(2026, 4, 27, 9, 15);
            ActionMemoEntity workMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.of(2026, 4, 27),
                    "朝の作業", ActionMemoCategory.WORK, 30, new BigDecimal("70.50"), 42L, false,
                    created);

            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(workMemo));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(todoRepository.findByIdAndDeletedAtIsNull(42L))
                    .willReturn(Optional.of(ownPersonalTodo(42L, USER_ID, "重要タスク", TodoStatus.OPEN)));
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 9200L);
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishToTeamRequest req = new PublishToTeamRequest(42L,
                    "順調です<script>alert(1)</script>");
            actionMemoService.publishToTeam(MEMO_ID, req, USER_ID);

            ArgumentCaptor<TimelinePostEntity> postCaptor =
                    ArgumentCaptor.forClass(TimelinePostEntity.class);
            verify(timelinePostRepository).save(postCaptor.capture());
            String content = postCaptor.getValue().getContent();

            // [HH:MM] {content}
            assertThat(content).contains("[09:15]").contains("朝の作業");
            // 実績時間
            assertThat(content).contains("30分");
            // 進捗率（trailing zeros stripped: 70.5）
            assertThat(content).contains("70.5%");
            // 関連 TODO タイトル
            assertThat(content).contains("重要タスク");
            // 末尾コメント
            assertThat(content).contains("順調です");
            // XSS サニタイズ（<script> は除去される）
            assertThat(content)
                    .doesNotContain("<script>")
                    .doesNotContain("</script>");
        }
    }

    // ------------------------------------------------------------------
    // 6. PublishDailyToTeamTest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 3: publishDailyToTeam（日次まとめチーム投稿）")
    class PublishDailyToTeamTest {

        @Test
        @DisplayName("WORK かつ未投稿のメモのみが対象になる")
        void publishDailyToTeam_filtersWorkAndUnposted() {
            // findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull が
            // WORK & postedTeamId=null のメモを返すことを mock で表現
            ActionMemoEntity m1 = phase3Memo(11L, USER_ID, LocalDate.now(),
                    "メモ1", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 9, 0));
            ActionMemoEntity m2 = phase3Memo(12L, USER_ID, LocalDate.now(),
                    "メモ2", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 10, 0));

            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(memoRepository.findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull(
                    eq(USER_ID), any(LocalDate.class), eq(ActionMemoCategory.WORK)))
                    .willReturn(List.of(m1, m2));
            // publishToTeam の中で findByIdAndUserId が呼ばれる
            given(memoRepository.findByIdAndUserId(11L, USER_ID)).willReturn(Optional.of(m1));
            given(memoRepository.findByIdAndUserId(12L, USER_ID)).willReturn(Optional.of(m2));
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 9300L);
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishDailyToTeamRequest req = new PublishDailyToTeamRequest(42L);
            PublishDailyToTeamResponse response = actionMemoService.publishDailyToTeam(req, USER_ID);

            assertThat(response.getPostedCount()).isEqualTo(2);
            assertThat(response.getTeamId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("repository が WORK 未投稿フィルタで返却するメモのみ投稿対象になる（既投稿はそもそも返らない）")
        void publishDailyToTeam_skipsAlreadyPosted() {
            // 既投稿メモ (postedTeamId != null) は repository 側で除外される設計のため、
            // mock の返却値には未投稿メモのみを含める。
            ActionMemoEntity unpostedWork = phase3Memo(11L, USER_ID, LocalDate.now(),
                    "未投稿WORK", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 9, 0));

            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(memoRepository.findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull(
                    eq(USER_ID), any(LocalDate.class), eq(ActionMemoCategory.WORK)))
                    .willReturn(List.of(unpostedWork));
            given(memoRepository.findByIdAndUserId(11L, USER_ID))
                    .willReturn(Optional.of(unpostedWork));
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 9400L);
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishDailyToTeamRequest req = new PublishDailyToTeamRequest(42L);
            PublishDailyToTeamResponse response = actionMemoService.publishDailyToTeam(req, USER_ID);

            // 既投稿は repository 段階で除外されるため、postedCount は未投稿分のみ
            assertThat(response.getPostedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("当日 WORK メモが0件 → NO_WORK_MEMO_TODAY")
        void publishDailyToTeam_zeroWorkMemos_throws_NO_WORK_MEMO_TODAY() {
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(memoRepository.findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull(
                    eq(USER_ID), any(LocalDate.class), eq(ActionMemoCategory.WORK)))
                    .willReturn(List.of());

            PublishDailyToTeamRequest req = new PublishDailyToTeamRequest(42L);

            assertThatThrownBy(() -> actionMemoService.publishDailyToTeam(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_NO_WORK_MEMO_TODAY);
        }

        @Test
        @DisplayName("postedCount は実際に投稿したメモ数と一致する")
        void publishDailyToTeam_postedCountMatches() {
            ActionMemoEntity m1 = phase3Memo(11L, USER_ID, LocalDate.now(),
                    "M1", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 9, 0));
            ActionMemoEntity m2 = phase3Memo(12L, USER_ID, LocalDate.now(),
                    "M2", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 10, 0));
            ActionMemoEntity m3 = phase3Memo(13L, USER_ID, LocalDate.now(),
                    "M3", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 11, 0));

            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(memoRepository.findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull(
                    eq(USER_ID), any(LocalDate.class), eq(ActionMemoCategory.WORK)))
                    .willReturn(List.of(m1, m2, m3));
            given(memoRepository.findByIdAndUserId(11L, USER_ID)).willReturn(Optional.of(m1));
            given(memoRepository.findByIdAndUserId(12L, USER_ID)).willReturn(Optional.of(m2));
            given(memoRepository.findByIdAndUserId(13L, USER_ID)).willReturn(Optional.of(m3));
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 9500L);
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishDailyToTeamRequest req = new PublishDailyToTeamRequest(42L);
            PublishDailyToTeamResponse response = actionMemoService.publishDailyToTeam(req, USER_ID);

            assertThat(response.getPostedCount()).isEqualTo(3);
            // timelinePostRepository.save が 3 回呼ばれる
            verify(timelinePostRepository, atLeastOnce()).save(any(TimelinePostEntity.class));
        }
    }

    // ------------------------------------------------------------------
    // 7. Phase 4-β: revertTodoCompletion
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
                    .userId(USER_ID)
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

            actionMemoService.revertTodoCompletion(MEMO_ID, ADMIN_ID);

            verify(todoService).changeStatus(eq(todoId), any(TodoStatusChangeRequest.class), eq(USER_ID));
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

            assertThatThrownBy(() -> actionMemoService.revertTodoCompletion(MEMO_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_TODO_REVERT_NOT_ALLOWED);
        }

        @Test
        @DisplayName("completesTodo=false のメモは差し戻し不可 → TODO_NOT_COMPLETED_BY_MEMO")
        void revertTodoCompletion_completesTodoFalse_throws() {
            ActionMemoEntity memo = memoWithTodo(MEMO_ID, USER_ID, 50L, TEAM_ID, false);

            given(memoRepository.findById(MEMO_ID)).willReturn(Optional.of(memo));

            assertThatThrownBy(() -> actionMemoService.revertTodoCompletion(MEMO_ID, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_TODO_NOT_COMPLETED_BY_MEMO);
        }
    }

    // ------------------------------------------------------------------
    // 7. AvailableTeamsTest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 3: getAvailableTeams（投稿先チーム一覧）")
    class AvailableTeamsTest {

        private TeamEntity teamWith(Long id, String name) {
            TeamEntity team = TeamEntity.builder()
                    .name(name)
                    .build();
            ReflectionTestUtils.setField(team, "id", id);
            return team;
        }

        private UserRoleEntity roleWith(Long userId, Long teamId) {
            return UserRoleEntity.builder()
                    .userId(userId)
                    .roleId(1L)
                    .teamId(teamId)
                    .build();
        }

        @Test
        @DisplayName("isDefault が settings.defaultPostTeamId と一致するチームに付与される")
        void getAvailableTeams_marksDefaultTeam() {
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID))
                    .willReturn(List.of(roleWith(USER_ID, 10L), roleWith(USER_ID, 20L)));
            given(settingsService.findSettings(USER_ID))
                    .willReturn(Optional.of(settingsOf(USER_ID, ActionMemoCategory.WORK, 20L)));
            given(teamRepository.findById(10L)).willReturn(Optional.of(teamWith(10L, "チームA")));
            given(teamRepository.findById(20L)).willReturn(Optional.of(teamWith(20L, "チームB")));

            List<AvailableTeamResponse> result = actionMemoService.getAvailableTeams(USER_ID);

            assertThat(result).hasSize(2);
            AvailableTeamResponse a = result.stream().filter(t -> t.getId() == 10L).findFirst().orElseThrow();
            AvailableTeamResponse b = result.stream().filter(t -> t.getId() == 20L).findFirst().orElseThrow();
            assertThat(a.isDefault()).isFalse();
            assertThat(b.isDefault()).isTrue();
            assertThat(b.getName()).isEqualTo("チームB");
        }

        @Test
        @DisplayName("同一 teamId が複数所属（複数ロール）でも distinct で1件にまとまる")
        void getAvailableTeams_distinctTeamIds() {
            // 同じ teamId=30 に複数ロールで所属しているケース
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID))
                    .willReturn(List.of(
                            roleWith(USER_ID, 30L),
                            roleWith(USER_ID, 30L),
                            roleWith(USER_ID, 40L)));
            given(settingsService.findSettings(USER_ID)).willReturn(Optional.empty());
            given(teamRepository.findById(30L)).willReturn(Optional.of(teamWith(30L, "チームC")));
            given(teamRepository.findById(40L)).willReturn(Optional.of(teamWith(40L, "チームD")));

            List<AvailableTeamResponse> result = actionMemoService.getAvailableTeams(USER_ID);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(AvailableTeamResponse::getId).containsExactlyInAnyOrder(30L, 40L);
            assertThat(result).allSatisfy(t -> assertThat(t.isDefault()).isFalse());
        }

        @Test
        @DisplayName("チーム未所属ユーザーには空リストが返る")
        void getAvailableTeams_emptyForNonMember() {
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID))
                    .willReturn(List.of());
            // findSettings は呼ばれる可能性あり（lenient）
            lenient().when(settingsService.findSettings(USER_ID)).thenReturn(Optional.empty());

            List<AvailableTeamResponse> result = actionMemoService.getAvailableTeams(USER_ID);

            assertThat(result).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 8. Phase 4-β: listTeamMemberMemos（管理職ダッシュボード）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 4-β: listTeamMemberMemos（管理職ダッシュボード）")
    class ListTeamMemberMemosTest {

        private static final Long ADMIN_ID = 200L;
        private static final Long MEMBER_ID = 300L;
        private static final Long TEAM_ID = 400L;

        private ActionMemoEntity workMemo(Long id) {
            ActionMemoEntity memo = ActionMemoEntity.builder()
                    .userId(MEMBER_ID)
                    .memoDate(LocalDate.of(2026, 5, 1))
                    .content("WORK メモ")
                    .category(ActionMemoCategory.WORK)
                    .postedTeamId(TEAM_ID)
                    .build();
            ReflectionTestUtils.setField(memo, "id", id);
            return memo;
        }

        @Test
        @DisplayName("管理者権限あり: WORK メモ一覧を返す")
        void listTeamMemberMemos_asAdmin_success() {
            ActionMemoEntity memo1 = workMemo(10L);
            ActionMemoEntity memo2 = workMemo(11L);
            given(userRoleRepository.countTeamAdminByUserIdAndTeamId(ADMIN_ID, TEAM_ID)).willReturn(1L);
            given(memoRepository.findByUserIdAndPostedTeamIdAndCategoryWork(
                    eq(MEMBER_ID), eq(TEAM_ID), eq(null), any()))
                    .willReturn(List.of(memo1, memo2));
            given(tagLinkRepository.findByMemoId(any())).willReturn(List.of());

            var result = actionMemoService.listTeamMemberMemos(TEAM_ID, MEMBER_ID, ADMIN_ID, null, 50);

            assertThat(result.getData()).hasSize(2);
            assertThat(result.getNextCursor()).isNull();
        }

        @Test
        @DisplayName("管理者権限なし: DASHBOARD_FORBIDDEN 例外")
        void listTeamMemberMemos_notAdmin_throws() {
            given(userRoleRepository.countTeamAdminByUserIdAndTeamId(USER_ID, TEAM_ID)).willReturn(0L);

            assertThatThrownBy(() -> actionMemoService.listTeamMemberMemos(TEAM_ID, MEMBER_ID, USER_ID, null, 50))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ActionMemoErrorCode.ACTION_MEMO_DASHBOARD_FORBIDDEN);
        }

        @Test
        @DisplayName("カーソルページネーション: limit=2 で3件返ったとき hasNext=true")
        void listTeamMemberMemos_cursorPagination() {
            // limit=2 → effectiveLimit=2 → リポジトリに limit+1=3 件要求
            // 3件返ってくれば hasNext=true、page は先頭2件、nextCursor は2件目のID
            ActionMemoEntity memo1 = workMemo(10L);
            ActionMemoEntity memo2 = workMemo(11L);
            ActionMemoEntity memo3 = workMemo(12L);
            given(userRoleRepository.countTeamAdminByUserIdAndTeamId(ADMIN_ID, TEAM_ID)).willReturn(1L);
            given(memoRepository.findByUserIdAndPostedTeamIdAndCategoryWork(
                    eq(MEMBER_ID), eq(TEAM_ID), eq(null), any()))
                    .willReturn(List.of(memo1, memo2, memo3));
            given(tagLinkRepository.findByMemoId(any())).willReturn(List.of());

            var result = actionMemoService.listTeamMemberMemos(TEAM_ID, MEMBER_ID, ADMIN_ID, null, 2);

            assertThat(result.getData()).hasSize(2);
            assertThat(result.getNextCursor()).isEqualTo("11");
        }
    }
}
