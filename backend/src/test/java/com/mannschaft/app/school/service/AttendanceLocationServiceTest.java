package com.mannschaft.app.school.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AttendanceLocationService} 認可テスト（認可根治戦役 束4・AC-1-5）。
 *
 * <p>手本: {@link DailyAttendanceService}（{@code checkMembership}）。
 * getTimeline のみ「教職員（チーム所属）＋保護者（careLink）」の二経路認可
 * （マスター御裁可済み方針）を検証する。</p>
 *
 * <p>実装前（本テスト作成時点）は AccessControlService が未注入・未呼出のため、
 * 非所属/非教職員かつ非保護者のケースでも例外が飛ばず red になる。
 * 実装後は各メソッド冒頭の認可チェックにより green 化する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttendanceLocationService 認可テスト（束4・AC-1-5）")
class AttendanceLocationServiceTest {

    @Mock
    private DailyAttendanceRecordRepository dailyAttendanceRecordRepository;

    @Mock
    private PeriodAttendanceRecordRepository periodAttendanceRecordRepository;

    @Mock
    private AttendanceLocationChangeRepository attendanceLocationChangeRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private AttendanceLocationService attendanceLocationService;

    private static final Long TEAM_ID = 1L;
    private static final Long OPERATOR_USER_ID = 100L;
    private static final Long STUDENT_USER_ID = 201L;
    private static final Long OUTSIDER_USER_ID = 999L;
    private static final Long GUARDIAN_USER_ID = 500L;
    private static final LocalDate ATTENDANCE_DATE = LocalDate.of(2026, 7, 1);

    // ========================================
    // recordLocationChange（記録＝operator が対象チームに所属していること）
    // ========================================

    @Nested
    @DisplayName("recordLocationChange")
    class RecordLocationChange {

        @Test
        @DisplayName("AC-1-5 red→green: 対象チーム非所属operatorが記録 → 403 (COMMON_002)")
        void nonMemberOperator_forbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(OUTSIDER_USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> attendanceLocationService.recordLocationChange(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE,
                    AttendanceLocation.CLASSROOM, AttendanceLocation.SICK_BAY,
                    null, null, AttendanceLocationChangeReason.FELT_SICK, null, OUTSIDER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);

            verify(dailyAttendanceRecordRepository, never())
                    .findByTeamIdAndStudentUserIdAndAttendanceDate(any(), any(), any());
        }

        @Test
        @DisplayName("非回帰: チーム所属operatorは従来どおり記録可能")
        void memberOperator_success() {
            doNothing().when(accessControlService).checkMembership(OPERATOR_USER_ID, TEAM_ID, "TEAM");

            DailyAttendanceRecordEntity dailyRecord = DailyAttendanceRecordEntity.builder()
                    .teamId(TEAM_ID)
                    .studentUserId(STUDENT_USER_ID)
                    .attendanceDate(ATTENDANCE_DATE)
                    .build();
            given(dailyAttendanceRecordRepository
                    .findByTeamIdAndStudentUserIdAndAttendanceDate(TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(Optional.of(dailyRecord));
            given(attendanceLocationChangeRepository.save(any()))
                    .willAnswer(invocation -> invocation.getArgument(0));

            AttendanceLocationChangeEntity result = attendanceLocationService.recordLocationChange(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE,
                    AttendanceLocation.CLASSROOM, AttendanceLocation.SICK_BAY,
                    null, null, AttendanceLocationChangeReason.FELT_SICK, null, OPERATOR_USER_ID);

            assertThat(result.getToLocation()).isEqualTo(AttendanceLocation.SICK_BAY);
            verify(accessControlService).checkMembership(OPERATOR_USER_ID, TEAM_ID, "TEAM");
        }
    }

    // ========================================
    // getTeamLocationMap（チーム全体閲覧＝所属者のみ）
    // ========================================

    @Nested
    @DisplayName("getTeamLocationMap")
    class GetTeamLocationMap {

        @Test
        @DisplayName("AC-1-5 red→green: 対象チーム非所属ユーザーが GET /teams/{t}/attendance/locations → 403 (COMMON_002)")
        void nonMember_forbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(OUTSIDER_USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> attendanceLocationService
                    .getTeamLocationMap(TEAM_ID, ATTENDANCE_DATE, OUTSIDER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);

            verify(dailyAttendanceRecordRepository, never())
                    .findByTeamIdAndAttendanceDate(any(), any());
        }

        @Test
        @DisplayName("非回帰: チーム所属ユーザーは従来どおり一覧取得可能")
        void member_success() {
            doNothing().when(accessControlService).checkMembership(OPERATOR_USER_ID, TEAM_ID, "TEAM");
            given(dailyAttendanceRecordRepository.findByTeamIdAndAttendanceDate(TEAM_ID, ATTENDANCE_DATE))
                    .willReturn(List.of());
            given(attendanceLocationChangeRepository
                    .findByTeamIdAndAttendanceDateOrderByStudentUserIdAsc(TEAM_ID, ATTENDANCE_DATE))
                    .willReturn(List.of());

            var map = attendanceLocationService.getTeamLocationMap(TEAM_ID, ATTENDANCE_DATE, OPERATOR_USER_ID);

            assertThat(map).isEmpty();
            verify(accessControlService).checkMembership(OPERATOR_USER_ID, TEAM_ID, "TEAM");
        }
    }

    // ========================================
    // getTimeline（教職員＋保護者の二経路・マスター御裁可済み方針）
    // ========================================

    @Nested
    @DisplayName("getTimeline（教職員＋保護者の二経路）")
    class GetTimeline {

        @Test
        @DisplayName("AC-1-5: 教職員（同チーム所属）は生徒タイムラインを閲覧可能")
        void teacherMember_success() {
            DailyAttendanceRecordEntity dailyRecord = DailyAttendanceRecordEntity.builder()
                    .teamId(TEAM_ID)
                    .studentUserId(STUDENT_USER_ID)
                    .attendanceDate(ATTENDANCE_DATE)
                    .build();
            given(dailyAttendanceRecordRepository
                    .findFirstByStudentUserIdAndAttendanceDate(STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(Optional.of(dailyRecord));
            given(accessControlService.isMember(OPERATOR_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(attendanceLocationChangeRepository
                    .findByStudentUserIdAndAttendanceDateOrderByRecordedAtAsc(STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(List.of());

            var result = attendanceLocationService.getTimeline(STUDENT_USER_ID, ATTENDANCE_DATE, OPERATOR_USER_ID);

            assertThat(result).isEmpty();
            verify(accessControlService).isMember(OPERATOR_USER_ID, TEAM_ID, "TEAM");
            verify(accessControlService, never()).checkCareLink(any(), any());
        }

        @Test
        @DisplayName("AC-1-5: 保護者（careLink）は非所属でも生徒タイムラインを閲覧可能（2xx）")
        void guardianCareLink_success() {
            DailyAttendanceRecordEntity dailyRecord = DailyAttendanceRecordEntity.builder()
                    .teamId(TEAM_ID)
                    .studentUserId(STUDENT_USER_ID)
                    .attendanceDate(ATTENDANCE_DATE)
                    .build();
            given(dailyAttendanceRecordRepository
                    .findFirstByStudentUserIdAndAttendanceDate(STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(Optional.of(dailyRecord));
            given(accessControlService.isMember(GUARDIAN_USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            doNothing().when(accessControlService).checkCareLink(GUARDIAN_USER_ID, STUDENT_USER_ID);
            given(attendanceLocationChangeRepository
                    .findByStudentUserIdAndAttendanceDateOrderByRecordedAtAsc(STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(List.of());

            var result = attendanceLocationService.getTimeline(STUDENT_USER_ID, ATTENDANCE_DATE, GUARDIAN_USER_ID);

            assertThat(result).isEmpty();
            verify(accessControlService).checkCareLink(GUARDIAN_USER_ID, STUDENT_USER_ID);
        }

        @Test
        @DisplayName("AC-1-5 red→green: 非教職員かつ非保護者が GET タイムライン → 403 (COMMON_002)")
        void outsider_forbidden() {
            DailyAttendanceRecordEntity dailyRecord = DailyAttendanceRecordEntity.builder()
                    .teamId(TEAM_ID)
                    .studentUserId(STUDENT_USER_ID)
                    .attendanceDate(ATTENDANCE_DATE)
                    .build();
            given(dailyAttendanceRecordRepository
                    .findFirstByStudentUserIdAndAttendanceDate(STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(Optional.of(dailyRecord));
            given(accessControlService.isMember(OUTSIDER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkCareLink(OUTSIDER_USER_ID, STUDENT_USER_ID);

            assertThatThrownBy(() -> attendanceLocationService
                    .getTimeline(STUDENT_USER_ID, ATTENDANCE_DATE, OUTSIDER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("AC-1-5: 対象日の日次出欠記録が存在しなくても保護者(careLink)は閲覧可能")
        void guardianCareLink_noDailyRecord_success() {
            given(dailyAttendanceRecordRepository
                    .findFirstByStudentUserIdAndAttendanceDate(STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(Optional.empty());
            doNothing().when(accessControlService).checkCareLink(GUARDIAN_USER_ID, STUDENT_USER_ID);
            given(attendanceLocationChangeRepository
                    .findByStudentUserIdAndAttendanceDateOrderByRecordedAtAsc(STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(List.of());

            var result = attendanceLocationService.getTimeline(STUDENT_USER_ID, ATTENDANCE_DATE, GUARDIAN_USER_ID);

            assertThat(result).isEmpty();
            verify(accessControlService, never()).isMember(any(), any(), any());
        }

        @Test
        @DisplayName("AC-1-5 red→green: 日次出欠記録なし・非保護者 → 403 (COMMON_002)")
        void noDailyRecord_nonGuardian_forbidden() {
            given(dailyAttendanceRecordRepository
                    .findFirstByStudentUserIdAndAttendanceDate(STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(Optional.empty());
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkCareLink(OUTSIDER_USER_ID, STUDENT_USER_ID);

            assertThatThrownBy(() -> attendanceLocationService
                    .getTimeline(STUDENT_USER_ID, ATTENDANCE_DATE, OUTSIDER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }
}
