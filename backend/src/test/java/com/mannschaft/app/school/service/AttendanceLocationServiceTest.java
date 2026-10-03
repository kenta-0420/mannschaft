package com.mannschaft.app.school.service;

import com.mannschaft.app.school.dto.LocationChangeResponse;
import com.mannschaft.app.school.entity.AttendanceLocation;
import com.mannschaft.app.school.entity.AttendanceLocationChangeEntity;
import com.mannschaft.app.school.entity.AttendanceLocationChangeReason;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import com.mannschaft.app.school.repository.AttendanceLocationChangeRepository;
import com.mannschaft.app.school.repository.DailyAttendanceRecordRepository;
import com.mannschaft.app.school.repository.PeriodAttendanceRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AttendanceLocationService} の業務ロジックテスト。
 *
 * <p>認可（日次登録権 R・閲覧権 V・本人／保護者／教職員の範囲）はトランザクションの外の
 * {@link AttendanceLocationFacade} の責務であり、{@link AttendanceLocationFacadeTest} が検証する。
 * 本テストは、認可を通過した後の記録・範囲絞り込みを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttendanceLocationService 業務ロジックテスト")
class AttendanceLocationServiceTest {

    @Mock
    private DailyAttendanceRecordRepository dailyAttendanceRecordRepository;

    @Mock
    private PeriodAttendanceRecordRepository periodAttendanceRecordRepository;

    @Mock
    private AttendanceLocationChangeRepository attendanceLocationChangeRepository;

    @InjectMocks
    private AttendanceLocationService attendanceLocationService;

    private static final Long TEAM_ID = 1L;
    private static final Long TEAM_B_ID = 2L;
    private static final Long OPERATOR_USER_ID = 100L;
    private static final Long STUDENT_USER_ID = 201L;
    private static final LocalDate ATTENDANCE_DATE = LocalDate.of(2026, 7, 1);

    private DailyAttendanceRecordEntity dailyRecord() {
        return DailyAttendanceRecordEntity.builder()
                .teamId(TEAM_ID)
                .studentUserId(STUDENT_USER_ID)
                .attendanceDate(ATTENDANCE_DATE)
                .build();
    }

    private AttendanceLocationChangeEntity change(Long teamId) {
        return AttendanceLocationChangeEntity.builder()
                .teamId(teamId)
                .studentUserId(STUDENT_USER_ID)
                .attendanceDate(ATTENDANCE_DATE)
                .fromLocation(AttendanceLocation.CLASSROOM)
                .toLocation(AttendanceLocation.SICK_BAY)
                .reason(AttendanceLocationChangeReason.FELT_SICK)
                .recordedBy(OPERATOR_USER_ID)
                .build();
    }

    @Nested
    @DisplayName("recordLocationChange")
    class RecordLocationChange {

        @Test
        @DisplayName("場所変更を保存し、日次レコードの場所を更新する")
        void records() {
            given(dailyAttendanceRecordRepository
                    .findByTeamIdAndStudentUserIdAndAttendanceDate(TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(Optional.of(dailyRecord()));
            given(attendanceLocationChangeRepository.save(any()))
                    .willAnswer(invocation -> invocation.getArgument(0));

            AttendanceLocationChangeEntity result = attendanceLocationService.recordLocationChange(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE,
                    AttendanceLocation.CLASSROOM, AttendanceLocation.SICK_BAY,
                    null, null, AttendanceLocationChangeReason.FELT_SICK, null, OPERATOR_USER_ID);

            assertThat(result.getToLocation()).isEqualTo(AttendanceLocation.SICK_BAY);
        }

        @Test
        @DisplayName("AC-21: 時限記録はチーム条件付きで取得する（兼籍生徒の他クラスの記録を更新しない）")
        void periodRecords_areScopedByTeam() {
            given(dailyAttendanceRecordRepository
                    .findByTeamIdAndStudentUserIdAndAttendanceDate(TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(Optional.of(dailyRecord()));
            given(attendanceLocationChangeRepository.save(any()))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(periodAttendanceRecordRepository
                    .findByTeamIdAndStudentUserIdAndAttendanceDateOrderByPeriodNumberAsc(
                            TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(List.of());

            attendanceLocationService.recordLocationChange(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE,
                    AttendanceLocation.CLASSROOM, AttendanceLocation.SICK_BAY,
                    1, null, AttendanceLocationChangeReason.FELT_SICK, null, OPERATOR_USER_ID);

            verify(periodAttendanceRecordRepository).findByTeamIdAndStudentUserIdAndAttendanceDateOrderByPeriodNumberAsc(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE);
            verify(periodAttendanceRecordRepository, never())
                    .findByStudentUserIdAndAttendanceDateOrderByPeriodNumberAsc(any(), any());
        }
    }

    @Nested
    @DisplayName("getTeamLocationMap")
    class GetTeamLocationMap {

        @Test
        @DisplayName("日次レコードも場所変更も無ければ空のマップ")
        void empty() {
            given(dailyAttendanceRecordRepository.findByTeamIdAndAttendanceDate(TEAM_ID, ATTENDANCE_DATE))
                    .willReturn(List.of());
            given(attendanceLocationChangeRepository
                    .findByTeamIdAndAttendanceDateOrderByStudentUserIdAsc(TEAM_ID, ATTENDANCE_DATE))
                    .willReturn(List.of());

            assertThat(attendanceLocationService.getTeamLocationMap(TEAM_ID, ATTENDANCE_DATE)).isEmpty();
        }
    }

    @Nested
    @DisplayName("getTimeline（Facade が解決した返却範囲で絞る・AC-4）")
    class GetTimeline {

        @Test
        @DisplayName("範囲が指定されたら、そのクラス分だけが返る（他クラスの履歴は除外）")
        void scoped_returnsOnlyViewableClass() {
            given(attendanceLocationChangeRepository
                    .findByStudentUserIdAndAttendanceDateOrderByRecordedAtAsc(STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(List.of(change(TEAM_B_ID), change(TEAM_ID)));

            var result = attendanceLocationService.getTimeline(STUDENT_USER_ID, ATTENDANCE_DATE, Set.of(TEAM_ID));

            assertThat(result).extracting(LocationChangeResponse::getTeamId).containsExactly(TEAM_ID);
        }

        @Test
        @DisplayName("範囲が null（本人・保護者）なら全クラス分が返る")
        void unscoped_returnsAllClasses() {
            given(attendanceLocationChangeRepository
                    .findByStudentUserIdAndAttendanceDateOrderByRecordedAtAsc(STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(List.of(change(TEAM_B_ID), change(TEAM_ID)));

            var result = attendanceLocationService.getTimeline(STUDENT_USER_ID, ATTENDANCE_DATE, null);

            assertThat(result).hasSize(2);
        }
    }
}
