package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.dto.CreateShiftScheduleRequest;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * CMP-261004-1819: 作成Facadeの認可→親存在照会→書込の順序と失敗時の無書込を固定する。
 *
 * <p>存在照会前の製品コンストラクタでもコンパイルして実アサーションへ到達させるため@InjectMocksを使う。
 * TeamServiceの未注入や未呼出し自体を期待値で捕まえ、未実装APIやコンパイル失敗をredにしない。
 * 親照会用のstubだけlenientにし、是正前の未使用stub例外が本来のredを覆わないようにする。
 * 呼出し回数/順序は明示verifyで必ず検証し、全体のstrictnessは緩めない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-261004-1819 シフト作成Facadeの親存在境界")
class ShiftScheduleCreateTeamExistenceFacadeTest {

    private static final Long TEAM_ID = 1819L;
    private static final Long USER_ID = 10L;
    private static final CreateShiftScheduleRequest REQUEST = new CreateShiftScheduleRequest(
            "親存在契約", "WEEKLY", LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 7), null, null);

    @Mock private ShiftScheduleService scheduleService;
    @Mock private ShiftPreferenceReminderBatchService reminderService;
    @Mock private ScopeConcealingAccessGate accessGate;
    @Mock private AccessControlService accessControlService;
    @Mock private TeamService teamService;
    @InjectMocks private ShiftScheduleFacade facade;

    @ParameterizedTest(name = "AC-08/09 不在、SYSTEM_ADMIN={0}")
    @ValueSource(booleans = {true, false})
    void 親不在なら認可通過後も403で書込まない(boolean systemAdmin) {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(systemAdmin);
        // MockitoのOptional既定値emptyを利用し、未使用stubによる偽の失敗を作らない。
        Throwable failure = catchThrowable(() -> facade.createSchedule(TEAM_ID, REQUEST, USER_ID));

        assertAll(
                () -> {
                    assertThat(failure).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) failure).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002);
                },
                () -> verify(teamService).findTeamSummary(TEAM_ID),
                () -> verifyNoInteractions(scheduleService));
    }

    @Test
    @DisplayName("AC-08 親照会の例外は伝播し書込まない")
    void 親照会失敗を握りつぶさず書込まない() {
        RuntimeException lookupFailure = new IllegalStateException("親照会失敗の試験");
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
        lenient().when(teamService.findTeamSummary(TEAM_ID)).thenThrow(lookupFailure);

        Throwable failure = catchThrowable(() -> facade.createSchedule(TEAM_ID, REQUEST, USER_ID));

        assertAll(
                () -> assertThat(failure).isSameAs(lookupFailure),
                () -> verify(teamService).findTeamSummary(TEAM_ID),
                () -> verifyNoInteractions(scheduleService));
    }

    @ParameterizedTest(name = "AC-09 実在、SYSTEM_ADMIN={0}")
    @ValueSource(booleans = {true, false})
    void 認可と一回の親照会の後だけ書込む(boolean systemAdmin) {
        ShiftScheduleResponse response = ShiftScheduleResponse.builder().id(100L).teamId(TEAM_ID).build();
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(systemAdmin);
        lenient().when(teamService.findTeamSummary(TEAM_ID))
                .thenReturn(Optional.of(new TeamService.TeamSummary("存在するチーム", false)));
        given(scheduleService.createSchedule(TEAM_ID, REQUEST, USER_ID)).willReturn(response);

        assertThat(facade.createSchedule(TEAM_ID, REQUEST, USER_ID)).isSameAs(response);

        var order = inOrder(accessControlService, teamService, scheduleService);
        order.verify(accessControlService).isSystemAdmin(USER_ID);
        if (!systemAdmin) {
            order.verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
        } else {
            verify(accessControlService, never()).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
        }
        order.verify(teamService).findTeamSummary(TEAM_ID);
        order.verify(scheduleService).createSchedule(TEAM_ID, REQUEST, USER_ID);
        verifyNoMoreInteractions(teamService, scheduleService);
    }

    @Test
    @DisplayName("AC-09 非認可主体には親照会も書込も行わない（既存green回帰）")
    void 認可拒否なら親の存在を照会しない() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

        Throwable failure = catchThrowable(() -> facade.createSchedule(TEAM_ID, REQUEST, USER_ID));

        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002);
        verifyNoInteractions(teamService, scheduleService);
    }
}
