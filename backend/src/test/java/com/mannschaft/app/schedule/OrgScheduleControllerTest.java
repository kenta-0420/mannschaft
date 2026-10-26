package com.mannschaft.app.schedule;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.config.OrgScopeId;
import com.mannschaft.app.schedule.controller.OrgScheduleController;
import com.mannschaft.app.schedule.dto.AttendanceTeamBreakdownResponse;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.schedule.service.ScheduleReminderService;
import com.mannschaft.app.schedule.service.ScheduleScheduledTaskService;
import com.mannschaft.app.schedule.service.ScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link OrgScheduleController} の単体テスト。
 *
 * <p>本テストは (B) 組織→参加チーム配信 フェーズB の出欠チーム別内訳 EP に追加した
 * 組織 ADMIN 認可（{@code checkAdminOrAbove}）の番人を担う。team-breakdown 集計 EP と
 * CSV エクスポート EP の双方について、非 ADMIN が 403（{@code COMMON_002}）で弾かれること、
 * および ADMIN は正常に集計を取得できることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrgScheduleController 単体テスト（出欠チーム別内訳の組織ADMIN認可）")
class OrgScheduleControllerTest {

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private ScheduleAttendanceService attendanceService;

    @Mock
    private ScheduleReminderService reminderService;

    @Mock
    private ScheduleScheduledTaskService scheduledTaskService;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private OrgScheduleController controller;

    private static final Long ORG_ID = 100L;
    private static final Long SCHEDULE_ID = 30L;
    private static final Long USER_ID = 1L;

    private AttendanceTeamBreakdownResponse buildBreakdown() {
        return new AttendanceTeamBreakdownResponse(
                SCHEDULE_ID,
                new AttendanceTeamBreakdownResponse.TeamBreakdownCounts(45, 5, 10, 15),
                List.of(new AttendanceTeamBreakdownResponse.TeamBreakdownItem(
                        1L, "Aチーム", 20, 3, 4, 6)));
    }

    @Nested
    @DisplayName("getAttendanceTeamBreakdown（集計）")
    class GetTeamBreakdown {

        @Test
        @DisplayName("正常系: 組織ADMINはチーム別内訳を取得できる")
        void 集計_ADMIN正常() {
            try (MockedStatic<SecurityUtils> utils = mockStatic(SecurityUtils.class)) {
                utils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doNothing().when(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
                given(attendanceService.getAttendanceTeamBreakdown(SCHEDULE_ID)).willReturn(buildBreakdown());

                ResponseEntity<ApiResponse<AttendanceTeamBreakdownResponse>> result =
                        controller.getAttendanceTeamBreakdown(new OrgScopeId(ORG_ID), SCHEDULE_ID);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody()).isNotNull();
                assertThat(result.getBody().getData().getTotal().attending()).isEqualTo(45);
                assertThat(result.getBody().getData().getByTeam()).hasSize(1);
            }
        }

        @Test
        @DisplayName("番人: 非ADMINは403（COMMON_002）で弾かれ集計サービスは呼ばれない")
        void 集計_非ADMINは403() {
            try (MockedStatic<SecurityUtils> utils = mockStatic(SecurityUtils.class)) {
                utils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                        .when(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");

                assertThatThrownBy(() -> controller.getAttendanceTeamBreakdown(new OrgScopeId(ORG_ID), SCHEDULE_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                                .isEqualTo(CommonErrorCode.COMMON_002));

                verify(attendanceService, never()).getAttendanceTeamBreakdown(SCHEDULE_ID);
            }
        }
    }

    @Nested
    @DisplayName("exportAttendanceTeamBreakdownCsv（CSV）")
    class ExportTeamBreakdownCsv {

        @Test
        @DisplayName("正常系: 組織ADMINはチーム別内訳CSVを取得できる")
        void CSV_ADMIN正常() {
            try (MockedStatic<SecurityUtils> utils = mockStatic(SecurityUtils.class)) {
                utils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doNothing().when(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
                given(attendanceService.exportAttendanceTeamBreakdownCsv(SCHEDULE_ID))
                        .willReturn("チーム名,出席,一部参加,欠席,未回答,合計\n");

                ResponseEntity<byte[]> result =
                        controller.exportAttendanceTeamBreakdownCsv(new OrgScopeId(ORG_ID), SCHEDULE_ID);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("番人: 非ADMINは403（COMMON_002）で弾かれCSVサービスは呼ばれない")
        void CSV_非ADMINは403() {
            try (MockedStatic<SecurityUtils> utils = mockStatic(SecurityUtils.class)) {
                utils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                        .when(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");

                assertThatThrownBy(() -> controller.exportAttendanceTeamBreakdownCsv(new OrgScopeId(ORG_ID), SCHEDULE_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                                .isEqualTo(CommonErrorCode.COMMON_002));

                verify(attendanceService, never()).exportAttendanceTeamBreakdownCsv(SCHEDULE_ID);
            }
        }
    }
}
