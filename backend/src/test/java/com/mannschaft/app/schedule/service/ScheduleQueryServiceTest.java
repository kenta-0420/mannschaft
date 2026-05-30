package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleEventCategoryRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleQueryService} の単体テスト。
 *
 * <p>主目的は F00 認可基盤連携（2026-05-29）で追加した可視性フィルタの検証。
 * 取得系（getMyCalendar / listTeamSchedules / listOrgSchedules）が
 * {@link ContentVisibilityChecker#filterAccessible} を経由して非可視のチーム/組織予定を
 * 除外すること、個人予定はフィルタ対象外で常に含まれることを確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScheduleQueryService 単体テスト")
class ScheduleQueryServiceTest {

    @Mock private ScheduleRepository scheduleRepository;
    @Mock private NameResolverService nameResolverService;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private ScheduleEventCategoryRepository categoryRepository;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private ScheduleQueryService scheduleQueryService;

    private static final Long USER_ID = 100L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 6, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 6, 8, 0, 0);

    private ScheduleEntity schedule(Long id, Long teamId, Long orgId, Long userId) {
        ScheduleEntity entity = ScheduleEntity.builder()
                .teamId(teamId)
                .organizationId(orgId)
                .userId(userId)
                .title("予定" + id)
                .startAt(LocalDateTime.of(2026, 6, 2, 10, 0))
                .endAt(LocalDateTime.of(2026, 6, 2, 11, 0))
                .allDay(false)
                .eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    // ========================================
    // getMyCalendar
    // ========================================

    @Nested
    @DisplayName("getMyCalendar")
    class GetMyCalendar {

        @Test
        @DisplayName("認可漏れ回帰: 非可視のチーム/組織予定はカレンダーから除外される")
        void getMyCalendar_可視性フィルタで非可視のチーム組織予定を除外する() {
            // Given: 個人(1) は対象外で常に含まれる。チーム(10/11)・組織(20/21)。
            ScheduleEntity personal = schedule(1L, null, null, USER_ID);
            ScheduleEntity teamVisible = schedule(10L, TEAM_ID, null, null);
            ScheduleEntity teamHidden = schedule(11L, TEAM_ID, null, null);
            ScheduleEntity orgVisible = schedule(20L, null, ORG_ID, null);
            ScheduleEntity orgHidden = schedule(21L, null, ORG_ID, null);

            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(USER_ID, FROM, TO))
                    .willReturn(List.of(personal));
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID))
                    .willReturn(List.of(UserRoleEntity.builder().teamId(TEAM_ID).build()));
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID))
                    .willReturn(List.of(UserRoleEntity.builder().organizationId(ORG_ID).build()));
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(TEAM_ID, FROM, TO))
                    .willReturn(List.of(teamVisible, teamHidden));
            given(scheduleRepository.findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(ORG_ID, FROM, TO))
                    .willReturn(List.of(orgVisible, orgHidden));

            // team バッチ(10,11) → 10 のみ可視 / org バッチ(20,21) → 20 のみ可視
            given(contentVisibilityChecker.filterAccessible(
                    eq(ReferenceType.SCHEDULE), eq(List.of(10L, 11L)), eq(USER_ID)))
                    .willReturn(Set.of(10L));
            given(contentVisibilityChecker.filterAccessible(
                    eq(ReferenceType.SCHEDULE), eq(List.of(20L, 21L)), eq(USER_ID)))
                    .willReturn(Set.of(20L));

            // When
            List<CalendarEntryResponse> entries = scheduleQueryService.getMyCalendar(USER_ID, FROM, TO);

            // Then: 個人(1) + 可視チーム(10) + 可視組織(20)
            assertThat(entries).extracting(CalendarEntryResponse::getId)
                    .containsExactlyInAnyOrder(1L, 10L, 20L)
                    .doesNotContain(11L, 21L);
        }

        @Test
        @DisplayName("認可漏れ回帰: 個人予定は filterAccessible を通さず常に含まれる")
        void getMyCalendar_個人予定はフィルタ対象外() {
            // Given: チーム/組織所属なし、個人予定のみ
            ScheduleEntity personal = schedule(1L, null, null, USER_ID);
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(USER_ID, FROM, TO))
                    .willReturn(List.of(personal));
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());

            // When
            List<CalendarEntryResponse> entries = scheduleQueryService.getMyCalendar(USER_ID, FROM, TO);

            // Then: 個人予定は含まれ、team/org が空のため filterAccessible は呼ばれない
            assertThat(entries).extracting(CalendarEntryResponse::getId).containsExactly(1L);
            verify(contentVisibilityChecker, never())
                    .filterAccessible(eq(ReferenceType.SCHEDULE), any(), eq(USER_ID));
        }
    }

    // ========================================
    // listTeamSchedules
    // ========================================

    @Nested
    @DisplayName("listTeamSchedules")
    class ListTeamSchedules {

        @Test
        @DisplayName("認可漏れ回帰: 非可視のチーム予定は一覧から除外される")
        void listTeamSchedules_可視性フィルタで非可視予定を除外する() {
            // Given
            ScheduleEntity visible = schedule(10L, TEAM_ID, null, null);
            ScheduleEntity hidden = schedule(11L, TEAM_ID, null, null);
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(TEAM_ID, FROM, TO))
                    .willReturn(List.of(visible, hidden));
            given(contentVisibilityChecker.filterAccessible(
                    eq(ReferenceType.SCHEDULE), eq(List.of(10L, 11L)), eq(USER_ID)))
                    .willReturn(Set.of(10L));

            // When
            List<ScheduleResponse> result = scheduleQueryService.listTeamSchedules(TEAM_ID, FROM, TO, USER_ID);

            // Then
            assertThat(result).extracting(ScheduleResponse::getId).containsExactly(10L);
        }
    }

    // ========================================
    // listOrgSchedules
    // ========================================

    @Nested
    @DisplayName("listOrgSchedules")
    class ListOrgSchedules {

        @Test
        @DisplayName("認可漏れ回帰: 非可視の組織予定は一覧から除外される")
        void listOrgSchedules_可視性フィルタで非可視予定を除外する() {
            // Given
            ScheduleEntity visible = schedule(20L, null, ORG_ID, null);
            ScheduleEntity hidden = schedule(21L, null, ORG_ID, null);
            given(scheduleRepository.findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(ORG_ID, FROM, TO))
                    .willReturn(List.of(visible, hidden));
            given(contentVisibilityChecker.filterAccessible(
                    eq(ReferenceType.SCHEDULE), eq(List.of(20L, 21L)), eq(USER_ID)))
                    .willReturn(Set.of(20L));

            // When
            List<ScheduleResponse> result = scheduleQueryService.listOrgSchedules(ORG_ID, FROM, TO, USER_ID);

            // Then
            assertThat(result).extracting(ScheduleResponse::getId).containsExactly(20L);
        }
    }
}
