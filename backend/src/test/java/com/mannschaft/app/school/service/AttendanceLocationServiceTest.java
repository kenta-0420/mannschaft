package com.mannschaft.app.school.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.school.entity.AttendanceLocation;
import com.mannschaft.app.school.entity.AttendanceLocationChangeEntity;
import com.mannschaft.app.school.entity.AttendanceLocationChangeReason;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    @Mock
    private SchoolAttendanceAccessPolicy policy;

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
        @DisplayName("AC-13: 日次登録権（R）の無い operator が記録 → 403 (COMMON_002)")
        void nonWriter_forbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(policy).checkCanRecordDaily(OUTSIDER_USER_ID, TEAM_ID);

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
        @DisplayName("AC-13: 対象生徒がクラスの在籍メンバーでなければ 4xx（DAILY_RECORD_NOT_FOUND）で何も書かない")
        void studentNotEnrolled_rejected() {
            given(accessControlService.listActiveMemberIds(TEAM_ID, "TEAM")).willReturn(List.of(OPERATOR_USER_ID));

            assertThatThrownBy(() -> attendanceLocationService.recordLocationChange(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE,
                    AttendanceLocation.CLASSROOM, AttendanceLocation.SICK_BAY,
                    null, null, AttendanceLocationChangeReason.FELT_SICK, null, OPERATOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(SchoolErrorCode.DAILY_RECORD_NOT_FOUND);

            verify(attendanceLocationChangeRepository, never()).save(any());
        }

        @Test
        @DisplayName("非回帰: R を持つ operator は従来どおり記録可能")
        void writer_success() {
            given(accessControlService.listActiveMemberIds(TEAM_ID, "TEAM")).willReturn(List.of(STUDENT_USER_ID));

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
            verify(policy).checkCanRecordDaily(OPERATOR_USER_ID, TEAM_ID);
        }

        @Test
        @DisplayName("AC-21: 時限記録はチーム条件付きで取得する（兼籍生徒の他クラスの記録を更新しない）")
        void periodRecords_areScopedByTeam() {
            given(accessControlService.listActiveMemberIds(TEAM_ID, "TEAM")).willReturn(List.of(STUDENT_USER_ID));
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
                    .when(policy).checkCanView(OUTSIDER_USER_ID, TEAM_ID);

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
            doNothing().when(policy).checkCanView(OPERATOR_USER_ID, TEAM_ID);
            given(dailyAttendanceRecordRepository.findByTeamIdAndAttendanceDate(TEAM_ID, ATTENDANCE_DATE))
                    .willReturn(List.of());
            given(attendanceLocationChangeRepository
                    .findByTeamIdAndAttendanceDateOrderByStudentUserIdAsc(TEAM_ID, ATTENDANCE_DATE))
                    .willReturn(List.of());

            var map = attendanceLocationService.getTeamLocationMap(TEAM_ID, ATTENDANCE_DATE, OPERATOR_USER_ID);

            assertThat(map).isEmpty();
            verify(policy).checkCanView(OPERATOR_USER_ID, TEAM_ID);
        }
    }

    // ========================================
    // getTimeline（本人・保護者は全クラス分／教員は V のクラス分だけ・AC-4）
    // ========================================

    private static final Long TEAM_B_ID = 2L;

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
    @DisplayName("getTimeline（本人・保護者・V の教員）")
    class GetTimeline {

        private void notGuardian(Long userId) {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkCareLink(userId, STUDENT_USER_ID);
        }

        @Test
        @DisplayName("AC-4: 教員は自分が V のクラス分だけが返る（他クラスの履歴は除外）")
        void teacher_getsOnlyViewableClass() {
            notGuardian(OPERATOR_USER_ID);
            given(accessControlService.findActiveMembershipJoinedAtByScope(STUDENT_USER_ID, "TEAM"))
                    .willReturn(Map.of(TEAM_ID, LocalDateTime.now(), TEAM_B_ID, LocalDateTime.now()));
            given(policy.canView(OPERATOR_USER_ID, TEAM_ID)).willReturn(true);
            given(policy.canView(OPERATOR_USER_ID, TEAM_B_ID)).willReturn(false);
            given(attendanceLocationChangeRepository
                    .findByStudentUserIdAndAttendanceDateOrderByRecordedAtAsc(STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(List.of(change(TEAM_B_ID), change(TEAM_ID)));

            var result = attendanceLocationService.getTimeline(STUDENT_USER_ID, ATTENDANCE_DATE, OPERATOR_USER_ID);

            assertThat(result).extracting(AttendanceLocationChangeEntity::getTeamId).containsExactly(TEAM_ID);
        }

        @Test
        @DisplayName("AC-4: 生徒本人は全クラス分が返る（careLink 判定も行わない）")
        void self_getsAllClasses() {
            given(attendanceLocationChangeRepository
                    .findByStudentUserIdAndAttendanceDateOrderByRecordedAtAsc(STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(List.of(change(TEAM_B_ID), change(TEAM_ID)));

            var result = attendanceLocationService.getTimeline(STUDENT_USER_ID, ATTENDANCE_DATE, STUDENT_USER_ID);

            assertThat(result).hasSize(2);
            verify(accessControlService, never()).checkCareLink(any(), any());
        }

        @Test
        @DisplayName("AC-4: 保護者（careLink）は全クラス分が返る")
        void guardian_getsAllClasses() {
            doNothing().when(accessControlService).checkCareLink(GUARDIAN_USER_ID, STUDENT_USER_ID);
            given(attendanceLocationChangeRepository
                    .findByStudentUserIdAndAttendanceDateOrderByRecordedAtAsc(STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(List.of(change(TEAM_B_ID), change(TEAM_ID)));

            var result = attendanceLocationService.getTimeline(STUDENT_USER_ID, ATTENDANCE_DATE, GUARDIAN_USER_ID);

            assertThat(result).hasSize(2);
            verify(policy, never()).canView(any(), any());
        }

        @Test
        @DisplayName("AC-4: どのクラスでも V の無い者（同級の一般 MEMBER・別クラスの教員）は 403 (COMMON_002)")
        void noViewableClass_forbidden() {
            notGuardian(OUTSIDER_USER_ID);
            given(accessControlService.findActiveMembershipJoinedAtByScope(STUDENT_USER_ID, "TEAM"))
                    .willReturn(Map.of(TEAM_ID, LocalDateTime.now()));
            given(policy.canView(OUTSIDER_USER_ID, TEAM_ID)).willReturn(false);

            assertThatThrownBy(() -> attendanceLocationService
                    .getTimeline(STUDENT_USER_ID, ATTENDANCE_DATE, OUTSIDER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(attendanceLocationChangeRepository, never()).findByStudentUserIdAndAttendanceDateOrderByRecordedAtAsc(any(), any());
        }
    }
}
