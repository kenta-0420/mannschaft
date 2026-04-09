package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.dto.ActionMemoResponse;
import com.mannschaft.app.actionmemo.dto.CreateActionMemoRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyResponse;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoRequest;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagLinkRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
import static org.mockito.Mockito.verify;

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
}
