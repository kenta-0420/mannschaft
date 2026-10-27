package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.testsupport.VisibilityCheckerTestSupport;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleEventCategoryRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** 横断カレンダーのscope順と既存の重複要素を固定する。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CalendarBatchOrderTest {
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private NameResolverService nameResolverService;
    @Mock private MembershipService membershipService;
    @Mock private ScheduleEventCategoryRepository categoryRepository;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;
    @Mock private ScheduleAttendanceRepository attendanceRepository;
    @Mock private ScheduleTargetService scheduleTargetService;
    @Mock private CalendarLayerService calendarLayerService;
    @InjectMocks private ScheduleQueryService service;

    private static final Long USER = 81353L;
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime TO = FROM.plusMonths(1);

    @BeforeEach
    void setUp() {
        VisibilityCheckerTestSupport.allowAll(contentVisibilityChecker);
        lenient().when(scheduleTargetService.assignedScheduleIds(any(), eq(USER)))
                .thenAnswer(inv -> {
                    List<ScheduleEntity> schedules = inv.getArgument(0);
                    return schedules.stream().map(ScheduleEntity::getId).collect(Collectors.toSet());
                });
    }

    @Test
    void 個人と所属scope順を保持し重複所属だけを除外して予定の重複は維持する() {
        ScheduleEntity shared = schedule(1L, 20L, 30L, USER, 3);
        ScheduleEntity teamLater = schedule(2L, 20L, null, null, 5);
        ScheduleEntity secondTeam = schedule(3L, 10L, null, null, 1);
        ScheduleEntity organization = schedule(4L, null, 40L, null, 2);
        when(scheduleRepository.findCalendarByUserId(USER, FROM, TO)).thenReturn(List.of(shared));
        when(membershipService.getActiveTeamIdsIncludingRoleAssignments(USER)).thenReturn(List.of(20L, 10L, 20L));
        when(membershipService.getActiveOrgIdsIncludingRoleAssignments(USER)).thenReturn(List.of(40L, 30L, 40L));
        when(scheduleRepository.findCalendarByTeamId(20L, FROM, TO)).thenReturn(List.of(shared, teamLater));
        when(scheduleRepository.findCalendarByTeamId(10L, FROM, TO)).thenReturn(List.of(secondTeam));
        when(scheduleRepository.findCalendarByOrganizationId(40L, FROM, TO)).thenReturn(List.of(organization));
        when(scheduleRepository.findCalendarByOrganizationId(30L, FROM, TO)).thenReturn(List.of(shared));

        List<CalendarEntryResponse> entries = service.getMyCalendar(USER, FROM, TO);

        assertThat(entries).extracting(CalendarEntryResponse::getId).containsExactly(1L, 1L, 2L, 3L, 4L, 1L);
        assertThat(entries).extracting(entry -> entry.getScope().scopeType())
                .containsExactly("PERSONAL", "TEAM", "TEAM", "TEAM", "ORGANIZATION", "ORGANIZATION");
        assertThat(entries).extracting(entry -> entry.getScope().scopeId())
                .containsExactly(USER, 20L, 20L, 10L, 40L, 30L);
    }

    @Test
    void 途中の取得失敗を空カレンダー成功へ変換しない() {
        when(membershipService.getActiveTeamIdsIncludingRoleAssignments(USER)).thenReturn(List.of(20L));
        when(scheduleRepository.findCalendarByTeamId(20L, FROM, TO))
                .thenThrow(new IllegalStateException("calendar read failed"));
        assertThatThrownBy(() -> service.getMyCalendar(USER, FROM, TO))
                .isInstanceOf(IllegalStateException.class).hasMessage("calendar read failed");
    }

    private ScheduleEntity schedule(Long id, Long team, Long organization, Long user, int day) {
        return ScheduleEntity.builder().id(id).teamId(team).organizationId(organization).userId(user)
                .title("順序確認").startAt(FROM.plusDays(day)).endAt(FROM.plusDays(day).plusHours(1))
                .eventType(EventType.EVENT).status(ScheduleStatus.SCHEDULED).build();
    }
}
