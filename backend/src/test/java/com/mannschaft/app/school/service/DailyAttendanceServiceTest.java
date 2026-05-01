package com.mannschaft.app.school.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.dto.AttendanceHistoryItem;
import com.mannschaft.app.school.dto.DailyAttendanceListResponse;
import com.mannschaft.app.school.dto.DailyAttendanceResponse;
import com.mannschaft.app.school.dto.DailyAttendanceUpdateRequest;
import com.mannschaft.app.school.dto.DailyRollCallEntry;
import com.mannschaft.app.school.dto.DailyRollCallRequest;
import com.mannschaft.app.school.dto.DailyRollCallSummary;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.DailyAttendanceRecordRepository;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link DailyAttendanceService} 単体テスト。
 *
 * <p>設計書 §4.1 / §5.1 の日次出欠 CRUD を検証する:</p>
 * <ul>
 *   <li>submitDailyRollCall 正常系 — 新規登録成功（サマリ集計確認）</li>
 *   <li>submitDailyRollCall 正常系 — 既存レコードの upsert 更新</li>
 *   <li>getDailyAttendance 正常系 — 一覧・集計取得</li>
 *   <li>updateDailyRecord 正常系 — 部分更新</li>
 *   <li>updateDailyRecord 異常系 — DAILY_RECORD_NOT_FOUND</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DailyAttendanceServiceTest {

    @Mock
    private DailyAttendanceRecordRepository dailyAttendanceRecordRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private SchoolAttendanceNotificationService notificationService;

    @InjectMocks
    private DailyAttendanceService dailyAttendanceService;

    private static final Long TEAM_ID = 1L;
    private static final Long OPERATOR_USER_ID = 100L;
    private static final Long STUDENT_USER_ID_1 = 201L;
    private static final Long STUDENT_USER_ID_2 = 202L;
    private static final Long STUDENT_USER_ID_3 = 203L;
    private static final LocalDate ATTENDANCE_DATE = LocalDate.of(2026, 4, 30);

    // ========================================
    // テスト用エンティティビルダーヘルパー
    // ========================================

    private DailyAttendanceRecordEntity buildEntity(Long id, Long studentUserId, AttendanceStatus status) {
        DailyAttendanceRecordEntity entity = DailyAttendanceRecordEntity.builder()
                .teamId(TEAM_ID)
                .studentUserId(studentUserId)
                .attendanceDate(ATTENDANCE_DATE)
                .status(status)
                .recordedBy(OPERATOR_USER_ID)
                .build();
        if (id != null) {
            ReflectionTestUtils.setField(entity, "id", id);
        }
        return entity;
    }

    private DailyRollCallEntry buildEntry(Long studentUserId, AttendanceStatus status) {
        DailyRollCallEntry entry = new DailyRollCallEntry();
        ReflectionTestUtils.setField(entry, "studentUserId", studentUserId);
        ReflectionTestUtils.setField(entry, "status", status);
        return entry;
    }

    // ========================================
    // submitDailyRollCall テスト
    // ========================================

    @Nested
    @DisplayName("submitDailyRollCall")
    class SubmitDailyRollCall {

        @Test
        @DisplayName("正常系: 新規レコードを一括登録してサマリを返す")
        void success_newRecords() {
            // Arrange
            DailyRollCallEntry entry1 = buildEntry(STUDENT_USER_ID_1, AttendanceStatus.ATTENDING);
            DailyRollCallEntry entry2 = buildEntry(STUDENT_USER_ID_2, AttendanceStatus.ABSENT);
            DailyRollCallEntry entry3 = buildEntry(STUDENT_USER_ID_3, AttendanceStatus.UNDECIDED);

            DailyRollCallRequest request = new DailyRollCallRequest();
            ReflectionTestUtils.setField(request, "attendanceDate", ATTENDANCE_DATE);
            ReflectionTestUtils.setField(request, "entries", List.of(entry1, entry2, entry3));

            // checkMembership は void メソッドのため doNothing
            doNothing().when(accessControlService).checkMembership(OPERATOR_USER_ID, TEAM_ID, "TEAM");

            // 既存レコードなし
            given(dailyAttendanceRecordRepository.findByTeamIdAndStudentUserIdAndAttendanceDate(
                    any(), any(), any())).willReturn(Optional.empty());

            given(dailyAttendanceRecordRepository.save(any())).willAnswer(inv -> {
                DailyAttendanceRecordEntity e = inv.getArgument(0);
                ReflectionTestUtils.setField(e, "id", 1L);
                return e;
            });

            // Act
            DailyRollCallSummary summary = dailyAttendanceService.submitDailyRollCall(
                    TEAM_ID, request, OPERATOR_USER_ID);

            // Assert
            assertThat(summary.getAttendanceDate()).isEqualTo(ATTENDANCE_DATE);
            assertThat(summary.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(summary.getTotalCount()).isEqualTo(3);
            assertThat(summary.getPresentCount()).isEqualTo(1);
            assertThat(summary.getAbsentCount()).isEqualTo(1);
            assertThat(summary.getUndecidedCount()).isEqualTo(1);
            assertThat(summary.getRecordedAt()).isNotNull();

            verify(dailyAttendanceRecordRepository, times(3)).save(any());
            verify(notificationService, times(3)).notifyDailyAttendance(any(), any(), any());
        }

        @Test
        @DisplayName("正常系: 既存レコードがある場合は upsert で更新される")
        void success_upsertExistingRecord() {
            // Arrange
            DailyRollCallEntry entry = buildEntry(STUDENT_USER_ID_1, AttendanceStatus.ABSENT);

            DailyRollCallRequest request = new DailyRollCallRequest();
            ReflectionTestUtils.setField(request, "attendanceDate", ATTENDANCE_DATE);
            ReflectionTestUtils.setField(request, "entries", List.of(entry));

            doNothing().when(accessControlService).checkMembership(OPERATOR_USER_ID, TEAM_ID, "TEAM");

            // 既存レコードあり（UNDECIDED → ABSENT に更新）
            DailyAttendanceRecordEntity existingEntity = buildEntity(10L, STUDENT_USER_ID_1, AttendanceStatus.UNDECIDED);
            given(dailyAttendanceRecordRepository.findByTeamIdAndStudentUserIdAndAttendanceDate(
                    TEAM_ID, STUDENT_USER_ID_1, ATTENDANCE_DATE)).willReturn(Optional.of(existingEntity));

            given(dailyAttendanceRecordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // Act
            DailyRollCallSummary summary = dailyAttendanceService.submitDailyRollCall(
                    TEAM_ID, request, OPERATOR_USER_ID);

            // Assert
            assertThat(summary.getTotalCount()).isEqualTo(1);
            assertThat(summary.getAbsentCount()).isEqualTo(1);
            assertThat(summary.getPresentCount()).isEqualTo(0);

            // save が呼ばれたこと（更新パスを通過）を確認
            verify(dailyAttendanceRecordRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("正常系: PARTIAL ステータスは presentCount にカウントされる")
        void success_partialCountedAsPresent() {
            // Arrange
            DailyRollCallEntry entry = buildEntry(STUDENT_USER_ID_1, AttendanceStatus.PARTIAL);

            DailyRollCallRequest request = new DailyRollCallRequest();
            ReflectionTestUtils.setField(request, "attendanceDate", ATTENDANCE_DATE);
            ReflectionTestUtils.setField(request, "entries", List.of(entry));

            doNothing().when(accessControlService).checkMembership(OPERATOR_USER_ID, TEAM_ID, "TEAM");
            given(dailyAttendanceRecordRepository.findByTeamIdAndStudentUserIdAndAttendanceDate(
                    any(), any(), any())).willReturn(Optional.empty());
            given(dailyAttendanceRecordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // Act
            DailyRollCallSummary summary = dailyAttendanceService.submitDailyRollCall(
                    TEAM_ID, request, OPERATOR_USER_ID);

            // Assert
            assertThat(summary.getPresentCount()).isEqualTo(1);
            assertThat(summary.getAbsentCount()).isEqualTo(0);
        }
    }

    // ========================================
    // getDailyAttendance テスト
    // ========================================

    @Nested
    @DisplayName("getDailyAttendance")
    class GetDailyAttendance {

        @Test
        @DisplayName("正常系: 日次出欠一覧と集計を返す")
        void success() {
            // Arrange
            doNothing().when(accessControlService).checkMembership(OPERATOR_USER_ID, TEAM_ID, "TEAM");

            List<DailyAttendanceRecordEntity> entities = List.of(
                    buildEntity(1L, STUDENT_USER_ID_1, AttendanceStatus.ATTENDING),
                    buildEntity(2L, STUDENT_USER_ID_2, AttendanceStatus.ABSENT),
                    buildEntity(3L, STUDENT_USER_ID_3, AttendanceStatus.UNDECIDED)
            );
            given(dailyAttendanceRecordRepository.findByTeamIdAndAttendanceDate(TEAM_ID, ATTENDANCE_DATE))
                    .willReturn(entities);

            // Act
            DailyAttendanceListResponse response =
                    dailyAttendanceService.getDailyAttendance(TEAM_ID, ATTENDANCE_DATE, OPERATOR_USER_ID);

            // Assert
            assertThat(response.getAttendanceDate()).isEqualTo(ATTENDANCE_DATE);
            assertThat(response.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(response.getRecords()).hasSize(3);
            assertThat(response.getTotalCount()).isEqualTo(3);
            assertThat(response.getPresentCount()).isEqualTo(1);
            assertThat(response.getAbsentCount()).isEqualTo(1);
            assertThat(response.getUndecidedCount()).isEqualTo(1);
        }
    }

    // ========================================
    // updateDailyRecord テスト
    // ========================================

    @Nested
    @DisplayName("updateDailyRecord")
    class UpdateDailyRecord {

        @Test
        @DisplayName("正常系: 部分更新で ABSENT に変更できる")
        void success() {
            // Arrange
            doNothing().when(accessControlService).checkMembership(OPERATOR_USER_ID, TEAM_ID, "TEAM");

            DailyAttendanceRecordEntity existingEntity = buildEntity(1L, STUDENT_USER_ID_1, AttendanceStatus.UNDECIDED);
            given(dailyAttendanceRecordRepository.findById(1L)).willReturn(Optional.of(existingEntity));
            given(dailyAttendanceRecordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            DailyAttendanceUpdateRequest request = new DailyAttendanceUpdateRequest();
            ReflectionTestUtils.setField(request, "status", AttendanceStatus.ABSENT);

            // Act
            DailyAttendanceResponse response =
                    dailyAttendanceService.updateDailyRecord(TEAM_ID, 1L, request, OPERATOR_USER_ID);

            // Assert
            assertThat(response.getStatus()).isEqualTo(AttendanceStatus.ABSENT);
            assertThat(response.getStudentUserId()).isEqualTo(STUDENT_USER_ID_1);
            verify(dailyAttendanceRecordRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("異常系: 存在しないレコードIDで DAILY_RECORD_NOT_FOUND が投げられる")
        void notFound() {
            // Arrange
            doNothing().when(accessControlService).checkMembership(OPERATOR_USER_ID, TEAM_ID, "TEAM");
            given(dailyAttendanceRecordRepository.findById(999L)).willReturn(Optional.empty());

            DailyAttendanceUpdateRequest request = new DailyAttendanceUpdateRequest();
            ReflectionTestUtils.setField(request, "status", AttendanceStatus.ABSENT);

            // Act & Assert
            assertThatThrownBy(() ->
                    dailyAttendanceService.updateDailyRecord(TEAM_ID, 999L, request, OPERATOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getErrorCode()).isEqualTo(SchoolErrorCode.DAILY_RECORD_NOT_FOUND);
                    });
        }

        @Test
        @DisplayName("異常系: 他チームのレコードIDで DAILY_RECORD_NOT_FOUND が投げられる")
        void notFound_differentTeam() {
            // Arrange
            Long differentTeamId = 999L;
            doNothing().when(accessControlService).checkMembership(OPERATOR_USER_ID, differentTeamId, "TEAM");

            // teamId が異なるエンティティ（TEAM_ID=1 のレコードを differentTeamId=999 で検索）
            DailyAttendanceRecordEntity entityOfOtherTeam = buildEntity(1L, STUDENT_USER_ID_1, AttendanceStatus.ATTENDING);
            given(dailyAttendanceRecordRepository.findById(1L)).willReturn(Optional.of(entityOfOtherTeam));

            DailyAttendanceUpdateRequest request = new DailyAttendanceUpdateRequest();
            ReflectionTestUtils.setField(request, "status", AttendanceStatus.ABSENT);

            // Act & Assert
            assertThatThrownBy(() ->
                    dailyAttendanceService.updateDailyRecord(differentTeamId, 1L, request, OPERATOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getErrorCode()).isEqualTo(SchoolErrorCode.DAILY_RECORD_NOT_FOUND);
                    });
        }
    }
}
