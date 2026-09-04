package com.mannschaft.app.school.listener;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity.EvaluationStatus;
import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.entity.ClassHomeroomEntity;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import com.mannschaft.app.school.entity.FamilyAttendanceNoticeEntity;
import com.mannschaft.app.school.event.AttendanceRequirementStatusChangedEvent;
import com.mannschaft.app.school.event.DailyRollCallRecordedEvent;
import com.mannschaft.app.school.event.FamilyAttendanceNoticeSubmittedEvent;
import com.mannschaft.app.school.repository.AttendanceRequirementRuleRepository;
import com.mannschaft.app.school.repository.ClassHomeroomRepository;
import com.mannschaft.app.school.repository.DailyAttendanceRecordRepository;
import com.mannschaft.app.school.repository.FamilyAttendanceNoticeRepository;
import com.mannschaft.app.school.service.SchoolAttendanceNotificationService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SchoolAttendanceNotificationListener} 単体テスト（Issue #2990 L6）。
 *
 * <p>検証するのは正規形の 2 点である:</p>
 * <ul>
 *   <li>受信者 1 人ぶんの通知失敗が他の受信者を巻き添えにしない（ループ内 try/catch の隔離）</li>
 *   <li>業務データの読み直しに失敗した場合は握りつぶさず配送を中止する</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SchoolAttendanceNotificationListener 単体テスト（#2990 L6）")
class SchoolAttendanceNotificationListenerTest {

    @Mock
    private SchoolAttendanceNotificationService notificationService;

    @Mock
    private DailyAttendanceRecordRepository dailyAttendanceRecordRepository;

    @Mock
    private FamilyAttendanceNoticeRepository familyAttendanceNoticeRepository;

    @Mock
    private AttendanceRequirementRuleRepository ruleRepository;

    @Mock
    private ClassHomeroomRepository homeroomRepository;

    @InjectMocks
    private SchoolAttendanceNotificationListener listener;

    private static final Long TEAM_ID = 77L;

    @Test
    @DisplayName("生徒1人の保護者通知が失敗しても、残りの生徒には通知される")
    void 生徒1人の失敗が他を巻き添えにしない() {
        DailyAttendanceRecordEntity r1 = record(1L, 201L, AttendanceStatus.ATTENDING);
        DailyAttendanceRecordEntity r2 = record(2L, 202L, AttendanceStatus.ABSENT);
        DailyAttendanceRecordEntity r3 = record(3L, 203L, AttendanceStatus.PARTIAL);
        given(dailyAttendanceRecordRepository.findAllById(List.of(1L, 2L, 3L)))
                .willReturn(List.of(r1, r2, r3));

        // 2 人目だけ失敗させる。
        willThrow(new RuntimeException("模擬通知失敗"))
                .given(notificationService).notifyDailyAttendance(eq(202L), any(), any());

        assertThatCode(() -> listener.onDailyRollCallRecorded(
                new DailyRollCallRecordedEvent(TEAM_ID, List.of(1L, 2L, 3L))))
                .doesNotThrowAnyException();

        // 1 人目・3 人目には通知が届いている（＝2 人目の失敗で打ち切られていない）。
        verify(notificationService).notifyDailyAttendance(eq(201L), any(), eq(AttendanceStatus.ATTENDING));
        verify(notificationService).notifyDailyAttendance(eq(202L), any(), eq(AttendanceStatus.ABSENT));
        verify(notificationService).notifyDailyAttendance(eq(203L), any(), eq(AttendanceStatus.PARTIAL));
    }

    @Test
    @DisplayName("出欠レコードの読み直しに失敗したら配送を中止する（例外は外へ出さない）")
    void 読み直し失敗で配送中止() {
        given(dailyAttendanceRecordRepository.findAllById(any()))
                .willThrow(new RuntimeException("模擬DB障害"));

        assertThatCode(() -> listener.onDailyRollCallRecorded(
                new DailyRollCallRecordedEvent(TEAM_ID, List.of(1L))))
                .doesNotThrowAnyException();

        verify(notificationService, never()).notifyDailyAttendance(any(), any(), any());
    }

    @Test
    @DisplayName("保護者連絡が見つからなければ配送を中止する")
    void 保護者連絡不在で配送中止() {
        given(familyAttendanceNoticeRepository.findById(500L)).willReturn(Optional.empty());

        assertThatCode(() -> listener.onFamilyNoticeSubmitted(
                new FamilyAttendanceNoticeSubmittedEvent(500L)))
                .doesNotThrowAnyException();

        verify(notificationService, never()).notifyFamilyNoticeSubmitted(any());
    }

    @Test
    @DisplayName("保護者連絡を読み直して担任へ通知する")
    void 保護者連絡送信通知を配送する() {
        FamilyAttendanceNoticeEntity notice = FamilyAttendanceNoticeEntity.builder()
                .teamId(TEAM_ID)
                .studentUserId(201L)
                .submitterUserId(301L)
                .attendanceDate(LocalDate.of(2026, 5, 12))
                .build();
        given(familyAttendanceNoticeRepository.findById(501L)).willReturn(Optional.of(notice));

        listener.onFamilyNoticeSubmitted(new FamilyAttendanceNoticeSubmittedEvent(501L));

        verify(notificationService).notifyFamilyNoticeSubmitted(notice);
    }

    @Test
    @DisplayName("出席要件 WARNING は規程・担任を読み直して教員へ通知する")
    void 出席要件通知を配送する() {
        AttendanceRequirementRuleEntity rule = AttendanceRequirementRuleEntity.builder()
                .teamId(TEAM_ID)
                .academicYear((short) 2026)
                .name("出席率規程")
                .build();
        ReflectionTestUtils.setField(rule, "id", 900L);
        ClassHomeroomEntity homeroom = ClassHomeroomEntity.builder()
                .teamId(TEAM_ID)
                .academicYear(2026)
                .homeroomTeacherUserId(999L)
                .build();

        given(ruleRepository.findById(900L)).willReturn(Optional.of(rule));
        given(homeroomRepository.findByTeamIdAndAcademicYearAndEffectiveUntilIsNull(eq(TEAM_ID), eq(2026)))
                .willReturn(Optional.of(homeroom));

        listener.onRequirementStatusChanged(
                new AttendanceRequirementStatusChangedEvent(200L, 900L, EvaluationStatus.WARNING));

        verify(notificationService).notifyRequirementWarning(eq(200L), eq("出席率規程"), eq(List.of(999L)));
    }

    @Test
    @DisplayName("規程が見つからなければ配送を中止する")
    void 規程不在で配送中止() {
        given(ruleRepository.findById(anyLong())).willReturn(Optional.empty());

        assertThatCode(() -> listener.onRequirementStatusChanged(
                new AttendanceRequirementStatusChangedEvent(200L, 901L, EvaluationStatus.RISK)))
                .doesNotThrowAnyException();

        verify(notificationService, never()).notifyRequirementRisk(any(), any(), any());
    }

    private DailyAttendanceRecordEntity record(Long id, Long studentUserId, AttendanceStatus status) {
        DailyAttendanceRecordEntity e = DailyAttendanceRecordEntity.builder()
                .teamId(TEAM_ID)
                .studentUserId(studentUserId)
                .attendanceDate(LocalDate.of(2026, 5, 11))
                .status(status)
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }
}
