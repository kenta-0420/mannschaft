package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.CreateShiftScheduleRequest;
import com.mannschaft.app.shift.dto.ManualRemindResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftScheduleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ShiftScheduleFacade}（認可ファサード）の単体テスト。
 *
 * <p>CMP-260923-0954 W6a: 旧 {@code ShiftScheduleServiceTest}・{@code ShiftPreferenceReminderBatchServiceTest}
 * が持っていた認可の検証（越境の 404 隠蔽・同チーム権限不足の 403・SUPPORTER・SYSTEM_ADMIN 短絡）を、
 * 認可の移設先である Facade へ移したもの（消していない）。ゲートは実物
 * （{@link ScopeConcealingAccessGate}）を {@link AccessControlService} のモックの上に組む。
 * 応答の status・code・message の全体は {@code ShiftScheduleSlotFacadeContractIT} が実 DB で固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftScheduleFacade 認可 単体テスト")
class ShiftScheduleFacadeTest {

    private static final Long TEAM_ID = 1L;
    private static final Long SCHEDULE_ID = 100L;
    private static final Long USER_ID = 10L;

    @Mock
    private ShiftScheduleService scheduleService;
    @Mock
    private ShiftPreferenceReminderBatchService reminderService;
    @Mock
    private AccessControlService accessControlService;

    private ShiftScheduleFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ShiftScheduleFacade(scheduleService, reminderService,
                new ScopeConcealingAccessGate(accessControlService), accessControlService);
    }

    private static ShiftScheduleScope published() {
        return new ShiftScheduleScope(SCHEDULE_ID, TEAM_ID, ShiftScheduleStatus.PUBLISHED,
                true);
    }

    private static ShiftScheduleScope draft() {
        return new ShiftScheduleScope(SCHEDULE_ID, TEAM_ID, ShiftScheduleStatus.DRAFT, false);
    }

    private static void assertCode(Throwable thrown, ErrorCode expected) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(expected);
    }

    // ========================================
    // 一覧（teamId 直接指定。越境も不在も 403）
    // ========================================

    @Nested
    @DisplayName("listSchedules")
    class ListSchedules {

        @Test
        @DisplayName("非メンバーは 403 COMMON_002（越境も不在も同じ・隠さない）")
        void 非メンバーは403() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertCode(catchThrowable(() -> facade.listSchedules(TEAM_ID, null, null, USER_ID)),
                    CommonErrorCode.COMMON_002);
            verifyNoInteractions(scheduleService);
        }

        @Test
        @DisplayName("SUPPORTER は 403 COMMON_002")
        void SUPPORTERは403() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isSupporter(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertCode(catchThrowable(() -> facade.listSchedules(TEAM_ID, null, null, USER_ID)),
                    CommonErrorCode.COMMON_002);
            verifyNoInteractions(scheduleService);
        }

        @Test
        @DisplayName("一般メンバーは privileged=false で呼ぶ（未公開は一覧から除外）")
        void 一般メンバーは公開のみ() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(scheduleService.listSchedules(TEAM_ID, false)).willReturn(List.of());

            assertThat(facade.listSchedules(TEAM_ID, null, null, USER_ID)).isEmpty();
            verify(scheduleService).listSchedules(TEAM_ID, false);
        }

        @Test
        @DisplayName("期間指定は from・to が両方あるときだけ listSchedulesByPeriod を呼ぶ")
        void 期間指定() {
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 31);
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(scheduleService.listSchedulesByPeriod(TEAM_ID, from, to, true)).willReturn(List.of());

            assertThat(facade.listSchedules(TEAM_ID, from, to, USER_ID)).isEmpty();
            verify(scheduleService, never()).listSchedules(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は短絡して全量（チーム判定を撃たない）")
        void SYSTEM_ADMINは短絡() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            given(scheduleService.listSchedules(TEAM_ID, true)).willReturn(List.of());

            assertThat(facade.listSchedules(TEAM_ID, null, null, USER_ID)).isEmpty();
            verify(accessControlService, never()).isMember(anyLong(), anyLong(), any());
            verify(accessControlService, never()).isAdminOrAbove(anyLong(), anyLong(), any());
        }
    }

    // ========================================
    // 詳細（越境は不在と同じ 404）
    // ========================================

    @Nested
    @DisplayName("getSchedule")
    class GetSchedule {

        @Test
        @DisplayName("越境（所属していない）は不在と同じ 404 SHIFT_001（BOLA 封鎖・存在オラクル解消）")
        void 越境は404() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(published());
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertCode(catchThrowable(() -> facade.getSchedule(SCHEDULE_ID, USER_ID)),
                    ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            verify(scheduleService, never()).getSchedule(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("SUPPORTER は 403 COMMON_002")
        void SUPPORTERは403() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(published());
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isSupporter(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertCode(catchThrowable(() -> facade.getSchedule(SCHEDULE_ID, USER_ID)),
                    CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("未公開は認可結果より先に 404（SUPPORTER にも 403 を見せない）")
        void 未公開は404() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(draft());

            assertCode(catchThrowable(() -> facade.getSchedule(SCHEDULE_ID, USER_ID)),
                    ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        }

        @Test
        @DisplayName("一般メンバーは privileged=false で取得する")
        void 一般メンバーは通る() {
            ShiftScheduleResponse response = ShiftScheduleResponse.builder().id(SCHEDULE_ID).build();
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(published());
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(scheduleService.getSchedule(SCHEDULE_ID, false)).willReturn(response);

            assertThat(facade.getSchedule(SCHEDULE_ID, USER_ID)).isSameAs(response);
        }

        @Test
        @DisplayName("user_roles のみの ADMIN は在籍を問わず通り、未公開も見える（退行させない）")
        void userRolesのみのADMINは通る() {
            ShiftScheduleResponse response = ShiftScheduleResponse.builder().id(SCHEDULE_ID).build();
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(draft());
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(scheduleService.getSchedule(SCHEDULE_ID, true)).willReturn(response);

            assertThat(facade.getSchedule(SCHEDULE_ID, USER_ID)).isSameAs(response);
            verify(accessControlService, never()).isMember(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は短絡して通る")
        void SYSTEM_ADMINは短絡() {
            ShiftScheduleResponse response = ShiftScheduleResponse.builder().id(SCHEDULE_ID).build();
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(draft());
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            given(scheduleService.getSchedule(SCHEDULE_ID, true)).willReturn(response);

            assertThat(facade.getSchedule(SCHEDULE_ID, USER_ID)).isSameAs(response);
        }
    }

    // ========================================
    // 作成（teamId 直接指定。権限不足は 403）
    // ========================================

    @Nested
    @DisplayName("createSchedule")
    class CreateSchedule {

        private final CreateShiftScheduleRequest req = new CreateShiftScheduleRequest(
                "3月第1週シフト", "WEEKLY", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7), null, null);

        @Test
        @DisplayName("非権限者は 403 COMMON_002 で tx 本体を呼ばない")
        void 非権限者は403() {
            org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertCode(catchThrowable(() -> facade.createSchedule(TEAM_ID, req, USER_ID)),
                    CommonErrorCode.COMMON_002);
            verifyNoInteractions(scheduleService);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は短絡して作成できる（チーム ADMIN 判定を経ない）")
        void SYSTEM_ADMINは短絡() {
            ShiftScheduleResponse response = ShiftScheduleResponse.builder().id(SCHEDULE_ID).build();
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            given(scheduleService.createSchedule(TEAM_ID, req, USER_ID)).willReturn(response);

            assertThat(facade.createSchedule(TEAM_ID, req, USER_ID)).isSameAs(response);
            verify(accessControlService, never()).checkAdminOrAbove(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("チーム ADMIN は作成できる")
        void ADMINは作成できる() {
            ShiftScheduleResponse response = ShiftScheduleResponse.builder().id(SCHEDULE_ID).build();
            given(scheduleService.createSchedule(TEAM_ID, req, USER_ID)).willReturn(response);

            assertThat(facade.createSchedule(TEAM_ID, req, USER_ID)).isSameAs(response);
            verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
        }
    }

    // ========================================
    // 管理系（更新・削除・遷移・サマリ・remind・複製）: 越境 404 / 権限不足 403 / 管理者・SA は通る
    // ========================================

    @Nested
    @DisplayName("管理系 EP（更新・削除・遷移・サマリ・remind・複製）")
    class AdminOperations {

        private final UpdateShiftScheduleRequest updateReq = new UpdateShiftScheduleRequest(
                "タイトル", null, null, null, null, null, null);

        @BeforeEach
        void scope() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(published());
        }

        private void asOutsider() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
        }

        private void asPlainMember() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        }

        private void asAdmin() {
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        }

        @Test
        @DisplayName("越境は不在と同じ 404 SHIFT_001（remind も。是正前は 403 でオラクルだった）で tx 本体を呼ばない")
        void 越境は404() {
            asOutsider();

            for (Runnable op : List.<Runnable>of(
                    () -> facade.updateSchedule(SCHEDULE_ID, updateReq, USER_ID),
                    () -> facade.deleteSchedule(SCHEDULE_ID, USER_ID),
                    () -> facade.transitionStatus(SCHEDULE_ID, "COLLECTING", USER_ID),
                    () -> facade.getScheduleSummary(SCHEDULE_ID, USER_ID),
                    () -> facade.remindUnsubmitted(SCHEDULE_ID, USER_ID),
                    () -> facade.duplicateSchedule(SCHEDULE_ID, USER_ID))) {
                assertCode(catchThrowable(op::run), ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            }
            verifyNoInteractions(reminderService);
            verify(scheduleService, never()).updateSchedule(anyLong(), any());
            verify(scheduleService, never()).deleteSchedule(anyLong(), anyLong());
            verify(scheduleService, never()).transitionStatus(anyLong(), any(), anyLong());
            verify(scheduleService, never()).getScheduleSummary(anyLong());
            verify(scheduleService, never()).duplicateSchedule(anyLong(), anyLong());
        }

        @Test
        @DisplayName("同チームの権限不足（一般メンバー）は 403 COMMON_002（隠す必要が無い側）")
        void 権限不足は403() {
            asPlainMember();

            for (Runnable op : List.<Runnable>of(
                    () -> facade.updateSchedule(SCHEDULE_ID, updateReq, USER_ID),
                    () -> facade.deleteSchedule(SCHEDULE_ID, USER_ID),
                    () -> facade.transitionStatus(SCHEDULE_ID, "PUBLISHED", USER_ID),
                    () -> facade.getScheduleSummary(SCHEDULE_ID, USER_ID),
                    () -> facade.remindUnsubmitted(SCHEDULE_ID, USER_ID),
                    () -> facade.duplicateSchedule(SCHEDULE_ID, USER_ID))) {
                assertCode(catchThrowable(op::run), CommonErrorCode.COMMON_002);
            }
            verifyNoInteractions(reminderService);
            verify(scheduleService, never()).transitionStatus(anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("チーム ADMIN は各 tx 本体へ進む（remind は手動リマインド本体へ）")
        void 管理者は通る() {
            asAdmin();
            ShiftScheduleResponse response = ShiftScheduleResponse.builder().id(SCHEDULE_ID).build();
            ShiftScheduleSummaryResponse summary = ShiftScheduleSummaryResponse.builder()
                    .scheduleId(SCHEDULE_ID).summaryByDate(List.of()).build();
            ManualRemindResponse remind = ManualRemindResponse.builder().scheduleId(SCHEDULE_ID).build();
            given(scheduleService.updateSchedule(SCHEDULE_ID, updateReq)).willReturn(response);
            given(scheduleService.transitionStatus(SCHEDULE_ID, "COLLECTING", USER_ID)).willReturn(response);
            given(scheduleService.getScheduleSummary(SCHEDULE_ID)).willReturn(summary);
            given(scheduleService.duplicateSchedule(SCHEDULE_ID, USER_ID)).willReturn(response);
            given(reminderService.triggerManualReminder(SCHEDULE_ID, USER_ID)).willReturn(remind);

            assertThat(facade.updateSchedule(SCHEDULE_ID, updateReq, USER_ID)).isSameAs(response);
            facade.deleteSchedule(SCHEDULE_ID, USER_ID);
            assertThat(facade.transitionStatus(SCHEDULE_ID, "COLLECTING", USER_ID)).isSameAs(response);
            assertThat(facade.getScheduleSummary(SCHEDULE_ID, USER_ID)).isSameAs(summary);
            assertThat(facade.remindUnsubmitted(SCHEDULE_ID, USER_ID)).isSameAs(remind);
            assertThat(facade.duplicateSchedule(SCHEDULE_ID, USER_ID)).isSameAs(response);
            verify(scheduleService).deleteSchedule(SCHEDULE_ID, USER_ID);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は短絡して通る（チーム判定を撃たない）")
        void SYSTEM_ADMINは短絡() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            ManualRemindResponse remind = ManualRemindResponse.builder().scheduleId(SCHEDULE_ID).build();
            given(reminderService.triggerManualReminder(SCHEDULE_ID, USER_ID)).willReturn(remind);

            assertThat(facade.remindUnsubmitted(SCHEDULE_ID, USER_ID)).isSameAs(remind);
            verify(accessControlService, never()).isAdminOrAbove(anyLong(), anyLong(), any());
            verify(accessControlService, never()).isMember(anyLong(), anyLong(), any());
        }
    }
}
