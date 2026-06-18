package com.mannschaft.app.schedule;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.EventSurveyService;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.schedule.service.ScheduleDelegationService;
import com.mannschaft.app.schedule.service.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleAttendanceService#openAttendanceSolicitation} の単体テスト（機能55 第二陣・RSVP 根治）。
 * メンバー解決・冪等・通知発火を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleAttendanceService.openAttendanceSolicitation 単体テスト")
class ScheduleAttendanceSolicitationServiceTest {

    @Mock
    private ScheduleAttendanceRepository attendanceRepository;
    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private ScheduleService scheduleService;
    @Mock
    private EventSurveyService eventSurveyService;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ProxyInputContext proxyInputContext;
    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;
    @Mock
    private ScheduleDelegationService scheduleDelegationService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private NotificationDispatchService notificationDispatchService;
    @Mock
    private OrganizationMembershipService organizationMembershipService;

    private ScheduleAttendanceService service;

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;

    @BeforeEach
    void setUp() {
        service = new ScheduleAttendanceService(
                attendanceRepository, scheduleRepository, scheduleService, eventSurveyService,
                userRoleRepository, eventPublisher, proxyInputContext, proxyInputRecordRepository,
                scheduleDelegationService,
                notificationService, notificationDispatchService,
                organizationMembershipService,
                accessControlService);
    }

    private ScheduleEntity teamSchedule() {
        return ScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("練習試合")
                .startAt(LocalDateTime.of(2026, 7, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 7, 1, 12, 0))
                .allDay(false)
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .createdBy(999L)
                .build();
    }

    @Test
    @DisplayName("TEAM予定_メンバー解決し出欠生成と募集通知が飛ぶ")
    void TEAM予定_メンバー解決し出欠生成と募集通知が飛ぶ() {
        // given
        given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(teamSchedule());
        given(attendanceRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID))
                .willReturn(List.of(201L, 202L));
        NotificationEntity dummy = org.mockito.Mockito.mock(NotificationEntity.class);
        given(notificationService.createNotification(
                anyLong(), any(), any(), any(), any(), any(), anyLong(), any(), anyLong(), any(), any()))
                .willReturn(dummy);

        // when
        service.openAttendanceSolicitation(SCHEDULE_ID);

        // then: 出欠レコード生成は generateAttendanceRecords 内で saveAll（バッチ）1回で行われる
        verify(attendanceRepository, times(1)).saveAll(any());
        verify(attendanceRepository, never()).save(any());
        // 募集通知は2名分作成・配信される
        verify(notificationService, times(2)).createNotification(
                anyLong(), eq("SCHEDULE_ATTENDANCE_REQUEST"), any(), any(), any(),
                eq("SCHEDULE"), eq(SCHEDULE_ID), any(), eq(TEAM_ID), any(), eq(999L));
        verify(notificationDispatchService, times(2)).dispatch(dummy);
    }

    private ScheduleEntity orgSchedule(boolean includeSupporters) {
        return ScheduleEntity.builder()
                .organizationId(ORG_ID)
                .title("組織総会")
                .startAt(LocalDateTime.of(2026, 7, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 7, 1, 12, 0))
                .allDay(false)
                .eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .includeSupporters(includeSupporters)
                .createdBy(999L)
                .build();
    }

    @Test
    @DisplayName("ORG予定_組織配信窓口で配下チーム展開し_SUPPORTER除外で出欠生成と通知が飛ぶ")
    void ORG予定_組織配信窓口で配下チーム展開し出欠生成と通知が飛ぶ() {
        // given: includeSupporters=false → SUPPORTER 除外で窓口を呼ぶ。
        //        TEAM 系の findUserIdsByScope は使わず、organization 窓口経由で解決すること。
        given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(orgSchedule(false));
        given(attendanceRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
        given(organizationMembershipService.resolveOrgDistributionUserIds(ORG_ID, false))
                .willReturn(List.of(301L, 302L, 303L));
        NotificationEntity dummy = org.mockito.Mockito.mock(NotificationEntity.class);
        given(notificationService.createNotification(
                anyLong(), any(), any(), any(), any(), any(), anyLong(), any(), anyLong(), any(), any()))
                .willReturn(dummy);

        // when
        service.openAttendanceSolicitation(SCHEDULE_ID);

        // then: 出欠レコードは saveAll（バッチ）で 1 回だけ呼ばれる（per-user save ではない）
        verify(attendanceRepository, times(1)).saveAll(any());
        verify(attendanceRepository, never()).save(any());
        // 募集通知は3名分作成・配信される（scope=ORGANIZATION・scopeId=ORG_ID）
        verify(notificationService, times(3)).createNotification(
                anyLong(), eq("SCHEDULE_ATTENDANCE_REQUEST"), any(), any(), any(),
                eq("SCHEDULE"), eq(SCHEDULE_ID), any(), eq(ORG_ID), any(), eq(999L));
        verify(notificationDispatchService, times(3)).dispatch(dummy);
        // TEAM 系の解決は一切使われないこと
        verify(userRoleRepository, never()).findUserIdsByScope(any(), anyLong());
    }

    @Test
    @DisplayName("ORG予定_includeSupporters=true_窓口にtrueを渡しSUPPORTERも含める")
    void ORG予定_includeSupportersがtrueなら窓口にtrueを渡す() {
        // given
        given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(orgSchedule(true));
        given(attendanceRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
        given(organizationMembershipService.resolveOrgDistributionUserIds(ORG_ID, true))
                .willReturn(List.of(301L, 302L));
        NotificationEntity dummy = org.mockito.Mockito.mock(NotificationEntity.class);
        given(notificationService.createNotification(
                anyLong(), any(), any(), any(), any(), any(), anyLong(), any(), anyLong(), any(), any()))
                .willReturn(dummy);

        // when
        service.openAttendanceSolicitation(SCHEDULE_ID);

        // then: includeSupporters=true で窓口が呼ばれ、2名分の通知が飛ぶ
        verify(organizationMembershipService).resolveOrgDistributionUserIds(ORG_ID, true);
        verify(attendanceRepository, times(1)).saveAll(any());
        verify(notificationDispatchService, times(2)).dispatch(dummy);
    }

    @Test
    @DisplayName("ORG予定_対象0名_出欠生成せずスキップ")
    void ORG予定_対象0名_出欠生成せずスキップ() {
        // given
        given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(orgSchedule(false));
        given(attendanceRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
        given(organizationMembershipService.resolveOrgDistributionUserIds(ORG_ID, false))
                .willReturn(List.of());

        // when
        service.openAttendanceSolicitation(SCHEDULE_ID);

        // then
        verify(attendanceRepository, never()).saveAll(any());
        verify(attendanceRepository, never()).save(any());
        verify(notificationService, never()).createNotification(
                anyLong(), any(), any(), any(), any(), any(), anyLong(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("既に出欠生成済み_冪等にスキップ")
    void 既に出欠生成済み_冪等にスキップ() {
        // given
        given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(teamSchedule());
        given(attendanceRepository.countByScheduleId(SCHEDULE_ID)).willReturn(5L);

        // when
        service.openAttendanceSolicitation(SCHEDULE_ID);

        // then: 生成も通知もされない
        verify(attendanceRepository, never()).save(any());
        verify(userRoleRepository, never()).findUserIdsByScope(any(), anyLong());
        verify(notificationService, never()).createNotification(
                anyLong(), any(), any(), any(), any(), any(), anyLong(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("PERSONAL予定_出欠募集の概念なくスキップ")
    void PERSONAL予定_出欠募集の概念なくスキップ() {
        // given
        ScheduleEntity personal = ScheduleEntity.builder()
                .userId(500L)
                .title("個人予定")
                .startAt(LocalDateTime.of(2026, 7, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 7, 1, 12, 0))
                .allDay(false)
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.ADMIN_ONLY)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .build();
        given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(personal);

        // when
        service.openAttendanceSolicitation(SCHEDULE_ID);

        // then
        verify(attendanceRepository, never()).save(any());
        verify(notificationService, never()).createNotification(
                anyLong(), any(), any(), any(), any(), any(), anyLong(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("対象メンバー0名_出欠生成せずスキップ")
    void 対象メンバー0名_出欠生成せずスキップ() {
        // given
        given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(teamSchedule());
        given(attendanceRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of());

        // when
        service.openAttendanceSolicitation(SCHEDULE_ID);

        // then
        verify(attendanceRepository, never()).save(any());
        verify(notificationService, never()).createNotification(
                anyLong(), any(), any(), any(), any(), any(), anyLong(), any(), anyLong(), any(), any());
    }
}
