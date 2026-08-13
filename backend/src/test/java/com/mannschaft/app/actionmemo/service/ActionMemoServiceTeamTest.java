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
 * {@link ActionMemoScopeService} / {@link ActionMemoAdminService} 単体テスト — チーム管理機能。
 *
 * <p>元ファイル ActionMemoServiceTest.java から分割。以下の @Nested クラスを含む:</p>
 * <ul>
 *   <li>AvailableTeamsTest</li>
 *   <li>ListTeamMemberMemosTest</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoService 単体テスト — チーム管理機能")
class ActionMemoServiceTeamTest {

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
    // AvailableTeamsTest
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
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(10L, 20L));
            given(settingsService.findSettings(USER_ID))
                    .willReturn(Optional.of(settingsOf(USER_ID, ActionMemoCategory.WORK, 20L)));
            given(teamRepository.findById(10L)).willReturn(Optional.of(teamWith(10L, "チームA")));
            given(teamRepository.findById(20L)).willReturn(Optional.of(teamWith(20L, "チームB")));

            List<AvailableTeamResponse> result = actionMemoScopeService.getAvailableTeams(USER_ID);

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
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(30L, 30L, 40L));
            given(settingsService.findSettings(USER_ID)).willReturn(Optional.empty());
            given(teamRepository.findById(30L)).willReturn(Optional.of(teamWith(30L, "チームC")));
            given(teamRepository.findById(40L)).willReturn(Optional.of(teamWith(40L, "チームD")));

            List<AvailableTeamResponse> result = actionMemoScopeService.getAvailableTeams(USER_ID);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(AvailableTeamResponse::getId).containsExactlyInAnyOrder(30L, 40L);
            assertThat(result).allSatisfy(t -> assertThat(t.isDefault()).isFalse());
        }

        @Test
        @DisplayName("チーム未所属ユーザーには空リストが返る")
        void getAvailableTeams_emptyForNonMember() {
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of());
            // findSettings は呼ばれる可能性あり（lenient）
            lenient().when(settingsService.findSettings(USER_ID)).thenReturn(Optional.empty());

            List<AvailableTeamResponse> result = actionMemoScopeService.getAvailableTeams(USER_ID);

            assertThat(result).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // ListTeamMemberMemosTest
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

            var result = actionMemoAdminService.listTeamMemberMemos(TEAM_ID, MEMBER_ID, ADMIN_ID, null, 50);

            assertThat(result.getData()).hasSize(2);
            assertThat(result.getNextCursor()).isNull();
        }

        @Test
        @DisplayName("管理者権限なし: DASHBOARD_FORBIDDEN 例外")
        void listTeamMemberMemos_notAdmin_throws() {
            given(userRoleRepository.countTeamAdminByUserIdAndTeamId(USER_ID, TEAM_ID)).willReturn(0L);

            assertThatThrownBy(() -> actionMemoAdminService.listTeamMemberMemos(TEAM_ID, MEMBER_ID, USER_ID, null, 50))
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

            var result = actionMemoAdminService.listTeamMemberMemos(TEAM_ID, MEMBER_ID, ADMIN_ID, null, 2);

            assertThat(result.getData()).hasSize(2);
            assertThat(result.getNextCursor()).isEqualTo("11");
        }
    }
}
