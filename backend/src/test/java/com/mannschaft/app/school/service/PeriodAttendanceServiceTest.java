package com.mannschaft.app.school.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.dto.PeriodAttendanceEntry;
import com.mannschaft.app.school.dto.PeriodAttendanceListResponse;
import com.mannschaft.app.school.dto.PeriodAttendanceRequest;
import com.mannschaft.app.school.dto.PeriodAttendanceResponse;
import com.mannschaft.app.school.dto.PeriodAttendanceSummary;
import com.mannschaft.app.school.dto.PeriodAttendanceUpdateRequest;
import com.mannschaft.app.school.dto.StudentTimelineResponse;
import com.mannschaft.app.school.entity.AttendanceTransitionAlertEntity;
import com.mannschaft.app.school.entity.PeriodAttendanceRecordEntity;
import com.mannschaft.app.school.entity.TransitionAlertLevel;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.AttendanceTransitionAlertRepository;
import com.mannschaft.app.school.repository.PeriodAttendanceRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link PeriodAttendanceService} 単体テスト。
 *
 * <p>設計書 §4.1・§5.2 の主要ユースケースを検証する:</p>
 * <ul>
 *   <li>submitPeriodAttendance 正常系（新規登録）</li>
 *   <li>getPeriodAttendance 正常系</li>
 *   <li>updatePeriodRecord 正常系・PERIOD_RECORD_NOT_FOUND 異常系</li>
 *   <li>AttendanceTransitionDetectionService: ABSENT→アラート生成・ATTENDING→empty</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PeriodAttendanceServiceTest {

    @Mock
    private PeriodAttendanceRecordRepository periodAttendanceRecordRepository;

    @Mock
    private AttendanceTransitionDetectionService attendanceTransitionDetectionService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private PeriodAttendanceService periodAttendanceService;

    private static final Long TEAM_ID = 1L;
    private static final Long STUDENT_USER_ID = 200L;
    private static final Long OPERATOR_USER_ID = 100L;
    private static final Integer PERIOD_NUMBER = 2;
    private static final LocalDate ATTENDANCE_DATE = LocalDate.of(2026, 4, 30);

    // ========================================
    // submitPeriodAttendance
    // ========================================

    @Nested
    @DisplayName("submitPeriodAttendance")
    class SubmitPeriodAttendance {

        @Test
        @DisplayName("正常系: 新規エントリを登録できる")
        void success_newRecord() {
            PeriodAttendanceEntry entry = new PeriodAttendanceEntry();
            ReflectionTestUtils.setField(entry, "studentUserId", STUDENT_USER_ID);
            ReflectionTestUtils.setField(entry, "status", AttendanceStatus.ATTENDING);

            PeriodAttendanceRequest request = new PeriodAttendanceRequest();
            ReflectionTestUtils.setField(request, "attendanceDate", ATTENDANCE_DATE);
            ReflectionTestUtils.setField(request, "entries", List.of(entry));

            PeriodAttendanceRecordEntity savedEntity = buildRecord(1L, AttendanceStatus.ATTENDING);

            given(periodAttendanceRecordRepository.findByTeamIdAndStudentUserIdAndAttendanceDateAndPeriodNumber(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE, PERIOD_NUMBER))
                    .willReturn(Optional.empty());
            given(periodAttendanceRecordRepository.save(any())).willReturn(savedEntity);

            PeriodAttendanceSummary summary =
                    periodAttendanceService.submitPeriodAttendance(TEAM_ID, PERIOD_NUMBER, request, OPERATOR_USER_ID);

            assertThat(summary.getTotalCount()).isEqualTo(1);
            assertThat(summary.getPresentCount()).isEqualTo(1);
            assertThat(summary.getAbsentCount()).isEqualTo(0);
            assertThat(summary.getAlertCount()).isEqualTo(0);
            assertThat(summary.getAttendanceDate()).isEqualTo(ATTENDANCE_DATE);
            assertThat(summary.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(summary.getPeriodNumber()).isEqualTo(PERIOD_NUMBER);
            verify(periodAttendanceRecordRepository).save(any());
        }

        @Test
        @DisplayName("正常系: ABSENT エントリで移動検知が呼び出され、アラートが生成される")
        void absent_triggersAlertDetection() {
            PeriodAttendanceEntry entry = new PeriodAttendanceEntry();
            ReflectionTestUtils.setField(entry, "studentUserId", STUDENT_USER_ID);
            ReflectionTestUtils.setField(entry, "status", AttendanceStatus.ABSENT);

            PeriodAttendanceRequest request = new PeriodAttendanceRequest();
            ReflectionTestUtils.setField(request, "attendanceDate", ATTENDANCE_DATE);
            ReflectionTestUtils.setField(request, "entries", List.of(entry));

            PeriodAttendanceRecordEntity savedEntity = buildRecord(1L, AttendanceStatus.ABSENT);

            given(periodAttendanceRecordRepository.findByTeamIdAndStudentUserIdAndAttendanceDateAndPeriodNumber(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE, PERIOD_NUMBER))
                    .willReturn(Optional.empty());
            given(periodAttendanceRecordRepository.save(any())).willReturn(savedEntity);

            AttendanceTransitionAlertEntity mockAlert = buildAlert();
            given(attendanceTransitionDetectionService.detectTransition(
                    eq(TEAM_ID), eq(STUDENT_USER_ID), eq(ATTENDANCE_DATE),
                    eq(PERIOD_NUMBER), eq(AttendanceStatus.ABSENT)))
                    .willReturn(Optional.of(mockAlert));

            PeriodAttendanceSummary summary =
                    periodAttendanceService.submitPeriodAttendance(TEAM_ID, PERIOD_NUMBER, request, OPERATOR_USER_ID);

            assertThat(summary.getAbsentCount()).isEqualTo(1);
            assertThat(summary.getAlertCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: ABSENT でも移動検知がアラートを生成しない場合は alertCount=0")
        void absent_noAlert() {
            PeriodAttendanceEntry entry = new PeriodAttendanceEntry();
            ReflectionTestUtils.setField(entry, "studentUserId", STUDENT_USER_ID);
            ReflectionTestUtils.setField(entry, "status", AttendanceStatus.ABSENT);

            PeriodAttendanceRequest request = new PeriodAttendanceRequest();
            ReflectionTestUtils.setField(request, "attendanceDate", ATTENDANCE_DATE);
            ReflectionTestUtils.setField(request, "entries", List.of(entry));

            PeriodAttendanceRecordEntity savedEntity = buildRecord(1L, AttendanceStatus.ABSENT);

            given(periodAttendanceRecordRepository.findByTeamIdAndStudentUserIdAndAttendanceDateAndPeriodNumber(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE, PERIOD_NUMBER))
                    .willReturn(Optional.empty());
            given(periodAttendanceRecordRepository.save(any())).willReturn(savedEntity);
            given(attendanceTransitionDetectionService.detectTransition(
                    any(), any(), any(), any(), any()))
                    .willReturn(Optional.empty());

            PeriodAttendanceSummary summary =
                    periodAttendanceService.submitPeriodAttendance(TEAM_ID, PERIOD_NUMBER, request, OPERATOR_USER_ID);

            assertThat(summary.getAbsentCount()).isEqualTo(1);
            assertThat(summary.getAlertCount()).isEqualTo(0);
        }
    }

    // ========================================
    // getPeriodAttendance
    // ========================================

    @Nested
    @DisplayName("getPeriodAttendance")
    class GetPeriodAttendance {

        @Test
        @DisplayName("正常系: 特定日・時限の出欠一覧を返す")
        void success() {
            PeriodAttendanceRecordEntity r1 = buildRecord(1L, AttendanceStatus.ATTENDING);
            PeriodAttendanceRecordEntity r2 = buildRecord(2L, AttendanceStatus.ABSENT);

            given(periodAttendanceRecordRepository.findByTeamIdAndAttendanceDateAndPeriodNumber(
                    TEAM_ID, ATTENDANCE_DATE, PERIOD_NUMBER))
                    .willReturn(List.of(r1, r2));

            PeriodAttendanceListResponse response =
                    periodAttendanceService.getPeriodAttendance(TEAM_ID, ATTENDANCE_DATE, PERIOD_NUMBER, OPERATOR_USER_ID);

            assertThat(response.getRecords()).hasSize(2);
            assertThat(response.getPresentCount()).isEqualTo(1);
            assertThat(response.getAbsentCount()).isEqualTo(1);
            assertThat(response.getAttendanceDate()).isEqualTo(ATTENDANCE_DATE);
            assertThat(response.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(response.getPeriodNumber()).isEqualTo(PERIOD_NUMBER);
        }

        @Test
        @DisplayName("正常系: 登録なしの場合は空一覧を返す")
        void empty() {
            given(periodAttendanceRecordRepository.findByTeamIdAndAttendanceDateAndPeriodNumber(
                    TEAM_ID, ATTENDANCE_DATE, PERIOD_NUMBER))
                    .willReturn(List.of());

            PeriodAttendanceListResponse response =
                    periodAttendanceService.getPeriodAttendance(TEAM_ID, ATTENDANCE_DATE, PERIOD_NUMBER, OPERATOR_USER_ID);

            assertThat(response.getRecords()).isEmpty();
            assertThat(response.getPresentCount()).isEqualTo(0);
            assertThat(response.getAbsentCount()).isEqualTo(0);
        }
    }

    // ========================================
    // updatePeriodRecord
    // ========================================

    @Nested
    @DisplayName("updatePeriodRecord")
    class UpdatePeriodRecord {

        @Test
        @DisplayName("正常系: ステータスを修正できる")
        void success() {
            PeriodAttendanceRecordEntity existing = buildRecord(10L, AttendanceStatus.ABSENT);

            PeriodAttendanceUpdateRequest updateRequest = new PeriodAttendanceUpdateRequest();
            ReflectionTestUtils.setField(updateRequest, "status", AttendanceStatus.ATTENDING);
            ReflectionTestUtils.setField(updateRequest, "comment", "修正済み");

            PeriodAttendanceRecordEntity updated = buildRecord(10L, AttendanceStatus.ATTENDING);

            given(periodAttendanceRecordRepository.findById(10L)).willReturn(Optional.of(existing));
            given(periodAttendanceRecordRepository.save(any())).willReturn(updated);

            PeriodAttendanceResponse response =
                    periodAttendanceService.updatePeriodRecord(TEAM_ID, 10L, updateRequest, OPERATOR_USER_ID);

            assertThat(response.getId()).isEqualTo(10L);
            assertThat(response.getStatus()).isEqualTo(AttendanceStatus.ATTENDING);
            verify(periodAttendanceRecordRepository).save(any());
        }

        @Test
        @DisplayName("異常系: 存在しない recordId を指定すると PERIOD_RECORD_NOT_FOUND")
        void notFound() {
            given(periodAttendanceRecordRepository.findById(999L)).willReturn(Optional.empty());

            PeriodAttendanceUpdateRequest updateRequest = new PeriodAttendanceUpdateRequest();
            ReflectionTestUtils.setField(updateRequest, "status", AttendanceStatus.ATTENDING);

            assertThatThrownBy(() ->
                    periodAttendanceService.updatePeriodRecord(TEAM_ID, 999L, updateRequest, OPERATOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SchoolErrorCode.PERIOD_RECORD_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: teamId が一致しないレコードを指定すると PERIOD_RECORD_NOT_FOUND")
        void teamMismatch() {
            // teamId=999 のレコードを TEAM_ID=1 で取得しようとする
            PeriodAttendanceRecordEntity wrongTeamRecord = buildRecord(10L, AttendanceStatus.ABSENT);
            ReflectionTestUtils.setField(wrongTeamRecord, "teamId", 999L);

            given(periodAttendanceRecordRepository.findById(10L)).willReturn(Optional.of(wrongTeamRecord));

            PeriodAttendanceUpdateRequest updateRequest = new PeriodAttendanceUpdateRequest();

            assertThatThrownBy(() ->
                    periodAttendanceService.updatePeriodRecord(TEAM_ID, 10L, updateRequest, OPERATOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SchoolErrorCode.PERIOD_RECORD_NOT_FOUND);
        }
    }

    // ========================================
    // getStudentDailyTimeline
    // ========================================

    @Nested
    @DisplayName("getStudentDailyTimeline")
    class GetStudentDailyTimeline {

        @Test
        @DisplayName("正常系: 本人が自分のタイムラインを取得できる")
        void success_ownTimeline() {
            PeriodAttendanceRecordEntity r1 = buildRecord(1L, AttendanceStatus.ATTENDING);
            PeriodAttendanceRecordEntity r2 = buildRecord(2L, AttendanceStatus.ABSENT);

            given(periodAttendanceRecordRepository
                    .findByStudentUserIdAndAttendanceDateOrderByPeriodNumberAsc(
                            STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(List.of(r1, r2));

            StudentTimelineResponse response =
                    periodAttendanceService.getStudentDailyTimeline(STUDENT_USER_ID, ATTENDANCE_DATE, STUDENT_USER_ID);

            assertThat(response.getStudentUserId()).isEqualTo(STUDENT_USER_ID);
            assertThat(response.getAttendanceDate()).isEqualTo(ATTENDANCE_DATE);
            assertThat(response.getPeriods()).hasSize(2);
        }

        @Test
        @DisplayName("異常系: 他の生徒のタイムラインにアクセスすると COMMON_002")
        void accessDenied_otherStudent() {
            Long otherStudentId = 999L;

            assertThatThrownBy(() ->
                    periodAttendanceService.getStudentDailyTimeline(otherStudentId, ATTENDANCE_DATE, STUDENT_USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // AttendanceTransitionDetectionService テスト
    // ========================================

    @Nested
    @DisplayName("AttendanceTransitionDetectionService.detectTransition")
    class DetectTransition {

        @Mock
        private PeriodAttendanceRecordRepository detectionRepository;

        @Mock
        private AttendanceTransitionAlertRepository detectionAlertRepository;

        @Test
        @DisplayName("正常系: 直前時限 ATTENDING かつ現在 ABSENT でアラートを生成する")
        void absent_afterAttending_generatesAlert() {
            AttendanceTransitionDetectionService detectionService =
                    new AttendanceTransitionDetectionService(
                            detectionRepository, detectionAlertRepository);

            PeriodAttendanceRecordEntity previousRecord = buildRecord(1L, AttendanceStatus.ATTENDING);
            ReflectionTestUtils.setField(previousRecord, "periodNumber", 1);

            given(detectionRepository
                    .findByTeamIdAndStudentUserIdAndAttendanceDateAndPeriodNumberLessThanOrderByPeriodNumberDesc(
                            TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE, PERIOD_NUMBER))
                    .willReturn(List.of(previousRecord));
            given(detectionAlertRepository
                    .existsByTeamIdAndStudentUserIdAndAttendanceDateAndResolvedAtIsNull(
                            TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(false);
            given(detectionAlertRepository.save(any())).willAnswer(inv -> {
                AttendanceTransitionAlertEntity e = inv.getArgument(0);
                ReflectionTestUtils.setField(e, "id", 10L);
                return e;
            });

            Optional<AttendanceTransitionAlertEntity> result = detectionService.detectTransition(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE, PERIOD_NUMBER, AttendanceStatus.ABSENT);

            assertThat(result).isPresent();
            assertThat(result.get().getStudentUserId()).isEqualTo(STUDENT_USER_ID);
            verify(detectionAlertRepository).save(any());
        }

        @Test
        @DisplayName("正常系: ATTENDING で呼び出すと empty を返す")
        void attending_returnsEmpty() {
            AttendanceTransitionDetectionService detectionService =
                    new AttendanceTransitionDetectionService(
                            detectionRepository, detectionAlertRepository);

            Optional<AttendanceTransitionAlertEntity> result = detectionService.detectTransition(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE, PERIOD_NUMBER, AttendanceStatus.ATTENDING);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: 直前時限がない場合は empty を返す")
        void noPreivousRecord_returnsEmpty() {
            AttendanceTransitionDetectionService detectionService =
                    new AttendanceTransitionDetectionService(
                            detectionRepository, detectionAlertRepository);

            given(detectionRepository
                    .findByTeamIdAndStudentUserIdAndAttendanceDateAndPeriodNumberLessThanOrderByPeriodNumberDesc(
                            TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE, PERIOD_NUMBER))
                    .willReturn(List.of());

            Optional<AttendanceTransitionAlertEntity> result = detectionService.detectTransition(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE, PERIOD_NUMBER, AttendanceStatus.ABSENT);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: 直前時限が ABSENT の場合（移動検知対象外）は empty を返す")
        void previousAbsent_returnsEmpty() {
            AttendanceTransitionDetectionService detectionService =
                    new AttendanceTransitionDetectionService(
                            detectionRepository, detectionAlertRepository);

            PeriodAttendanceRecordEntity previousRecord = buildRecord(1L, AttendanceStatus.ABSENT);
            ReflectionTestUtils.setField(previousRecord, "periodNumber", 1);

            given(detectionRepository
                    .findByTeamIdAndStudentUserIdAndAttendanceDateAndPeriodNumberLessThanOrderByPeriodNumberDesc(
                            TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE, PERIOD_NUMBER))
                    .willReturn(List.of(previousRecord));

            Optional<AttendanceTransitionAlertEntity> result = detectionService.detectTransition(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE, PERIOD_NUMBER, AttendanceStatus.ABSENT);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: 未解決アラートが既存の場合は重複生成しない")
        void existingUnresolvedAlert_skips() {
            AttendanceTransitionDetectionService detectionService =
                    new AttendanceTransitionDetectionService(
                            detectionRepository, detectionAlertRepository);

            PeriodAttendanceRecordEntity previousRecord = buildRecord(1L, AttendanceStatus.ATTENDING);
            ReflectionTestUtils.setField(previousRecord, "periodNumber", 1);

            given(detectionRepository
                    .findByTeamIdAndStudentUserIdAndAttendanceDateAndPeriodNumberLessThanOrderByPeriodNumberDesc(
                            TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE, PERIOD_NUMBER))
                    .willReturn(List.of(previousRecord));
            given(detectionAlertRepository
                    .existsByTeamIdAndStudentUserIdAndAttendanceDateAndResolvedAtIsNull(
                            TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE))
                    .willReturn(true);

            Optional<AttendanceTransitionAlertEntity> result = detectionService.detectTransition(
                    TEAM_ID, STUDENT_USER_ID, ATTENDANCE_DATE, PERIOD_NUMBER, AttendanceStatus.ABSENT);

            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // テストデータヘルパー
    // ========================================

    private PeriodAttendanceRecordEntity buildRecord(Long id, AttendanceStatus status) {
        PeriodAttendanceRecordEntity entity = PeriodAttendanceRecordEntity.builder()
                .teamId(TEAM_ID)
                .studentUserId(STUDENT_USER_ID)
                .attendanceDate(ATTENDANCE_DATE)
                .periodNumber(PERIOD_NUMBER)
                .subjectName("未登録")
                .teacherUserId(OPERATOR_USER_ID)
                .status(status)
                .recordedBy(OPERATOR_USER_ID)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private AttendanceTransitionAlertEntity buildAlert() {
        AttendanceTransitionAlertEntity alert = AttendanceTransitionAlertEntity.builder()
                .teamId(TEAM_ID)
                .studentUserId(STUDENT_USER_ID)
                .attendanceDate(ATTENDANCE_DATE)
                .previousPeriodNumber(1)
                .currentPeriodNumber(PERIOD_NUMBER)
                .previousPeriodStatus(AttendanceStatus.ATTENDING)
                .currentPeriodStatus(AttendanceStatus.ABSENT)
                .alertLevel(TransitionAlertLevel.NORMAL)
                .notifiedUsers("[]")
                .build();
        ReflectionTestUtils.setField(alert, "id", 1L);
        return alert;
    }
}
