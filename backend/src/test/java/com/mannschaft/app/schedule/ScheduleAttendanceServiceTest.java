package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.dto.AttendanceRequest;
import com.mannschaft.app.schedule.dto.AttendanceResponse;
import com.mannschaft.app.schedule.dto.AttendanceStatsResponse;
import com.mannschaft.app.schedule.dto.AttendanceSummaryResponse;
import com.mannschaft.app.schedule.dto.BulkAttendanceRequest;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.EventSurveyService;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.schedule.service.ScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleAttendanceService} の単体テスト。
 * 出欠回答・集計・CSV出力・統計を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleAttendanceService 単体テスト")
class ScheduleAttendanceServiceTest {

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

    @InjectMocks
    private ScheduleAttendanceService attendanceService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long TEAM_ID = 10L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 4, 1, 10, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 4, 1, 12, 0);
    private static final LocalDateTime FUTURE_DEADLINE = LocalDateTime.of(2099, 12, 31, 23, 59);

    private ScheduleEntity createScheduleWithAttendance() {
        return ScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("練習")
                .startAt(START)
                .endAt(END)
                .allDay(false)
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .attendanceDeadline(FUTURE_DEADLINE)
                .commentOption(CommentOption.OPTIONAL)
                .isException(false)
                .createdBy(USER_ID)
                .build();
    }

    private ScheduleEntity createScheduleWithoutAttendance() {
        return ScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("お知らせ")
                .startAt(START)
                .endAt(END)
                .allDay(false)
                .eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .isException(false)
                .build();
    }

    private ScheduleAttendanceEntity createAttendanceEntity(AttendanceStatus status) {
        return ScheduleAttendanceEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .userId(USER_ID)
                .status(status)
                .build();
    }

    // ========================================
    // respondAttendance
    // ========================================

    @Nested
    @DisplayName("respondAttendance")
    class RespondAttendance {

        @Test
        @DisplayName("出欠回答_正常_保存されてイベント発行される")
        void 出欠回答_正常_保存されてイベント発行される() {
            // given
            ScheduleEntity schedule = createScheduleWithAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            ScheduleAttendanceEntity attendance = createAttendanceEntity(AttendanceStatus.UNDECIDED);
            given(attendanceRepository.findByScheduleIdAndUserId(SCHEDULE_ID, USER_ID))
                    .willReturn(Optional.of(attendance));
            given(attendanceRepository.save(any(ScheduleAttendanceEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            AttendanceRequest req = new AttendanceRequest("ATTENDING", "参加します", null);

            // when
            AttendanceResponse result = attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req);

            // then
            assertThat(result.getStatus()).isEqualTo("ATTENDING");
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("出欠回答_出欠管理対象外_例外スロー")
        void 出欠回答_出欠管理対象外_例外スロー() {
            // given
            ScheduleEntity schedule = createScheduleWithoutAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            // when & then
            assertThatThrownBy(() -> attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.ATTENDANCE_NOT_REQUIRED);
        }

        @Test
        @DisplayName("出欠回答_期限超過_例外スロー")
        void 出欠回答_期限超過_例外スロー() {
            // given
            ScheduleEntity schedule = ScheduleEntity.builder()
                    .teamId(TEAM_ID)
                    .title("練習")
                    .startAt(START)
                    .endAt(END)
                    .allDay(false)
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(true)
                    .attendanceDeadline(LocalDateTime.of(2020, 1, 1, 0, 0))
                    .commentOption(CommentOption.OPTIONAL)
                    .isException(false)
                    .build();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            // when & then
            assertThatThrownBy(() -> attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.ATTENDANCE_DEADLINE_PASSED);
        }

        @Test
        @DisplayName("出欠回答_コメント必須なのに空_例外スロー")
        void 出欠回答_コメント必須なのに空_例外スロー() {
            // given
            ScheduleEntity schedule = ScheduleEntity.builder()
                    .teamId(TEAM_ID)
                    .title("練習")
                    .startAt(START)
                    .endAt(END)
                    .allDay(false)
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(true)
                    .attendanceDeadline(FUTURE_DEADLINE)
                    .commentOption(CommentOption.REQUIRED)
                    .isException(false)
                    .build();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            AttendanceRequest req = new AttendanceRequest("ABSENT", null, null);

            // when & then
            assertThatThrownBy(() -> attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.COMMENT_REQUIRED);
        }
    }

    // ========================================
    // getAttendances
    // ========================================

    @Nested
    @DisplayName("getAttendances")
    class GetAttendances {

        @Test
        @DisplayName("出欠一覧取得_正常_一覧を返す")
        void 出欠一覧取得_正常_一覧を返す() {
            // given
            ScheduleEntity schedule = createScheduleWithAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            ScheduleAttendanceEntity attendance = createAttendanceEntity(AttendanceStatus.ATTENDING);
            given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(SCHEDULE_ID))
                    .willReturn(List.of(attendance));

            // when
            List<AttendanceResponse> result = attendanceService.getAttendances(SCHEDULE_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("ATTENDING");
        }
    }

    // ========================================
    // getAttendanceSummary
    // ========================================

    @Nested
    @DisplayName("getAttendanceSummary")
    class GetAttendanceSummary {

        @Test
        @DisplayName("出欠サマリー取得_正常_集計結果を返す")
        void 出欠サマリー取得_正常_集計結果を返す() {
            // given
            ScheduleEntity schedule = createScheduleWithAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            Object[] row1 = new Object[]{AttendanceStatus.ATTENDING, 3L};
            Object[] row2 = new Object[]{AttendanceStatus.ABSENT, 1L};
            given(attendanceRepository.countByScheduleIdGroupByStatus(SCHEDULE_ID))
                    .willReturn(List.of(row1, row2));

            // when
            AttendanceSummaryResponse result = attendanceService.getAttendanceSummary(SCHEDULE_ID);

            // then
            assertThat(result.getAttending()).isEqualTo(3);
            assertThat(result.getAbsent()).isEqualTo(1);
            assertThat(result.getTotal()).isEqualTo(4);
        }
    }

    // ========================================
    // bulkUpdateAttendances
    // ========================================

    @Nested
    @DisplayName("bulkUpdateAttendances")
    class BulkUpdateAttendances {

        @Test
        @DisplayName("一括更新_正常_出欠が更新される")
        void 一括更新_正常_出欠が更新される() {
            // given
            ScheduleEntity schedule = createScheduleWithAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            ScheduleAttendanceEntity attendance = createAttendanceEntity(AttendanceStatus.UNDECIDED);
            given(attendanceRepository.findByScheduleIdAndUserId(SCHEDULE_ID, USER_ID))
                    .willReturn(Optional.of(attendance));
            given(attendanceRepository.save(any(ScheduleAttendanceEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            BulkAttendanceRequest req = new BulkAttendanceRequest(
                    List.of(new BulkAttendanceRequest.BulkAttendanceItem(USER_ID, "ATTENDING", "管理者承認")));

            // when
            attendanceService.bulkUpdateAttendances(SCHEDULE_ID, req);

            // then
            verify(attendanceRepository).save(any(ScheduleAttendanceEntity.class));
        }

        @Test
        @DisplayName("一括更新_出欠管理対象外_例外スロー")
        void 一括更新_出欠管理対象外_例外スロー() {
            // given
            ScheduleEntity schedule = createScheduleWithoutAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            BulkAttendanceRequest req = new BulkAttendanceRequest(
                    List.of(new BulkAttendanceRequest.BulkAttendanceItem(USER_ID, "ATTENDING", null)));

            // when & then
            assertThatThrownBy(() -> attendanceService.bulkUpdateAttendances(SCHEDULE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.ATTENDANCE_NOT_REQUIRED);
        }
    }

    // ========================================
    // exportAttendancesCsv
    // ========================================

    @Nested
    @DisplayName("exportAttendancesCsv")
    class ExportAttendancesCsv {

        @Test
        @DisplayName("CSV出力_正常_ヘッダーとデータを含む")
        void CSV出力_正常_ヘッダーとデータを含む() {
            // given
            ScheduleEntity schedule = createScheduleWithAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            ScheduleAttendanceEntity attendance = createAttendanceEntity(AttendanceStatus.ATTENDING);
            attendance.respond(AttendanceStatus.ATTENDING, "参加します");
            given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(SCHEDULE_ID))
                    .willReturn(List.of(attendance));

            // when
            String csv = attendanceService.exportAttendancesCsv(SCHEDULE_ID);

            // then
            assertThat(csv).startsWith("ユーザーID,ステータス,コメント,回答日時");
            assertThat(csv).contains("ATTENDING");
        }
    }

    // ========================================
    // generateAttendanceRecords
    // ========================================

    @Nested
    @DisplayName("generateAttendanceRecords")
    class GenerateAttendanceRecords {

        @Test
        @DisplayName("出欠レコード生成_3名分_3件保存される")
        void 出欠レコード生成_3名分_3件保存される() {
            // given
            given(attendanceRepository.save(any(ScheduleAttendanceEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            List<Long> memberIds = List.of(1L, 2L, 3L);

            // when
            attendanceService.generateAttendanceRecords(SCHEDULE_ID, memberIds);

            // then
            verify(attendanceRepository, org.mockito.Mockito.times(3))
                    .save(any(ScheduleAttendanceEntity.class));
        }
    }

    // ========================================
    // getMyAttendanceStats
    // ========================================

    @Nested
    @DisplayName("getMyAttendanceStats")
    class GetMyAttendanceStats {

        @Test
        @DisplayName("個人出席統計_出欠なし_出席率0を返す")
        void 個人出席統計_出欠なし_出席率0を返す() {
            // given
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());

            // when
            AttendanceStatsResponse result = attendanceService.getMyAttendanceStats(USER_ID, START, END);

            // then
            assertThat(result.getTotalSchedules()).isZero();
            assertThat(result.getAttendanceRate()).isZero();
        }
    }
}
