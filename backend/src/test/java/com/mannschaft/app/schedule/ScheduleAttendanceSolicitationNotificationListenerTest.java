package com.mannschaft.app.schedule;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.AttendanceSolicitationOpenedEvent;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.ScheduleAttendanceSolicitationNotificationListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleAttendanceSolicitationNotificationListener} の単体テスト（Issue #2990 L8）。
 *
 * <p>是正で通知が業務メソッドからリスナーへ移った結果、<b>受信者の決め方が
 * 「業務側で解決した List をそのまま使う」から「コミット済みの {@code schedule_attendances} を
 * 読み直す」へ変わった</b>。ここが是正で最も壊れやすい箇所なので、読み直しの結果が
 * 是正前と同じ宛先・同じ通知内容になることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Issue #2990 L8 出欠募集通知の配送リスナー 単体テスト")
class ScheduleAttendanceSolicitationNotificationListenerTest {

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;

    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private ScheduleAttendanceRepository attendanceRepository;

    @InjectMocks
    private ScheduleAttendanceSolicitationNotificationListener listener;

    private ScheduleEntity teamSchedule() {
        return ScheduleEntity.builder()
                .id(SCHEDULE_ID)
                .teamId(TEAM_ID)
                .title("練習試合")
                .startAt(LocalDateTime.of(2026, 7, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 7, 1, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .createdBy(999L)
                .build();
    }

    private ScheduleAttendanceEntity attendance(Long userId) {
        return ScheduleAttendanceEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .userId(userId)
                .status(AttendanceStatus.UNDECIDED)
                .build();
    }

    @Test
    @DisplayName("出欠レコードから受信者を読み直し、事前認可済みの一括通知として配信する")
    void 出欠レコードから受信者を読み直して配信する() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(teamSchedule()));
        given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(SCHEDULE_ID))
                .willReturn(List.of(attendance(201L), attendance(202L)));

        listener.onAttendanceSolicitationOpened(new AttendanceSolicitationOpenedEvent(SCHEDULE_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationHelper).notifyAllPreAuthorized(
                recipients.capture(),
                eq("SCHEDULE_ATTENDANCE_REQUEST"),
                eq(NotificationPriority.NORMAL),
                anyString(), anyString(),
                eq("SCHEDULE"), eq(SCHEDULE_ID),
                eq(NotificationScopeType.TEAM), eq(TEAM_ID),
                eq("/schedules/" + SCHEDULE_ID), eq(999L));
        assertThat(recipients.getValue())
                .as("受信者は業務TXで生成された出欠レコードの user_id（是正前の宛先集合と同一）")
                .containsExactly(201L, 202L);
    }

    @Test
    @DisplayName("組織スコープの予定は scope=ORGANIZATION / scopeId=organizationId で配信する")
    void 組織スコープは組織スコープで配信する() {
        ScheduleEntity org = ScheduleEntity.builder()
                .id(SCHEDULE_ID)
                .organizationId(ORG_ID)
                .title("組織総会")
                .startAt(LocalDateTime.of(2026, 7, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 7, 1, 12, 0))
                .eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .createdBy(999L)
                .build();
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(org));
        given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(SCHEDULE_ID))
                .willReturn(List.of(attendance(301L)));

        listener.onAttendanceSolicitationOpened(new AttendanceSolicitationOpenedEvent(SCHEDULE_ID));

        verify(notificationHelper).notifyAllPreAuthorized(
                anyList(), anyString(), any(NotificationPriority.class), anyString(), anyString(),
                anyString(), eq(SCHEDULE_ID),
                eq(NotificationScopeType.ORGANIZATION), eq(ORG_ID),
                anyString(), anyLong());
    }

    @Test
    @DisplayName("予定が読み直せない場合は配送を中止する（握りつぶさずログに残す）")
    void 予定が読み直せない場合は配送しない() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

        listener.onAttendanceSolicitationOpened(new AttendanceSolicitationOpenedEvent(SCHEDULE_ID));

        verify(notificationHelper, never()).notifyAllPreAuthorized(
                anyList(), anyString(), any(), anyString(), anyString(),
                anyString(), anyLong(), any(), anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("出欠レコードが0件なら配送しない")
    void 出欠レコードが0件なら配送しない() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(teamSchedule()));
        given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(SCHEDULE_ID)).willReturn(List.of());

        listener.onAttendanceSolicitationOpened(new AttendanceSolicitationOpenedEvent(SCHEDULE_ID));

        verify(notificationHelper, never()).notifyAllPreAuthorized(
                anyList(), anyString(), any(), anyString(), anyString(),
                anyString(), anyLong(), any(), anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("配送が例外を投げても呼び出し元（イベント配送基盤）へは伝播させない")
    void 配送失敗は呼び出し元へ伝播しない() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(teamSchedule()));
        given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(SCHEDULE_ID))
                .willReturn(List.of(attendance(201L)));
        willThrow(new RuntimeException("通知の永続化失敗"))
                .given(notificationHelper).notifyAllPreAuthorized(
                        anyList(), anyString(), any(), anyString(), anyString(),
                        anyString(), anyLong(), any(), anyLong(), anyString(), anyLong());

        assertThatCode(() -> listener.onAttendanceSolicitationOpened(
                new AttendanceSolicitationOpenedEvent(SCHEDULE_ID)))
                .as("通知の失敗はここで止める（業務側は既にコミット済みで巻き戻す対象が無い）")
                .doesNotThrowAnyException();
    }
}
