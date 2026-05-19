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
 * {@link ActionMemoPublishingService} 単体テスト — 投稿機能（publishDaily・publishToTeam・publishDailyToTeam）。
 *
 * <p>元ファイル ActionMemoServiceTest.java から分割。以下の @Nested クラスを含む:</p>
 * <ul>
 *   <li>PublishDailyTest</li>
 *   <li>PublishToTeamTest</li>
 *   <li>PublishDailyToTeamTest</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoService 単体テスト — 投稿機能")
class ActionMemoServicePublishTest {

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

    // ==================================================================
    // publishDaily（Phase 2）
    // ==================================================================

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

            PublishDailyResponse response = actionMemoPublishingService.publishDaily(req, USER_ID);

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

            PublishDailyResponse response = actionMemoPublishingService.publishDaily(req, USER_ID);

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

            assertThatThrownBy(() -> actionMemoPublishingService.publishDaily(req, USER_ID))
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

            actionMemoPublishingService.publishDaily(req, USER_ID);

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

    // ------------------------------------------------------------------
    // PublishToTeamTest
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
            PublishToTeamResponse response = actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID);

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

            assertThatThrownBy(() -> actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID))
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

            assertThatThrownBy(() -> actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID))
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

            assertThatThrownBy(() -> actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID))
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

            assertThatThrownBy(() -> actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID))
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
            PublishToTeamResponse response = actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID);

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
            actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID);

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
    // PublishDailyToTeamTest
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
            PublishDailyToTeamResponse response = actionMemoPublishingService.publishDailyToTeam(req, USER_ID);

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
            PublishDailyToTeamResponse response = actionMemoPublishingService.publishDailyToTeam(req, USER_ID);

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

            assertThatThrownBy(() -> actionMemoPublishingService.publishDailyToTeam(req, USER_ID))
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
            PublishDailyToTeamResponse response = actionMemoPublishingService.publishDailyToTeam(req, USER_ID);

            assertThat(response.getPostedCount()).isEqualTo(3);
            // timelinePostRepository.save が 3 回呼ばれる
            verify(timelinePostRepository, atLeastOnce()).save(any(TimelinePostEntity.class));
        }
    }
}
