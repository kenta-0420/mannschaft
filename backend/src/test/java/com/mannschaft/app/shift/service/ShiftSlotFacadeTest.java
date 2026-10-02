package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.BulkCreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.CreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import com.mannschaft.app.shift.dto.SlotAssignmentPatchRequest;
import com.mannschaft.app.shift.dto.UpdateShiftSlotRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftSlotFacade}（認可ファサード）の単体テスト。
 *
 * <p>CMP-260923-0954 W6a: 旧 {@code ShiftSlotService} が持っていた認可（越境の 404 隠蔽・同チーム権限不足の 403・
 * SUPPORTER・SYSTEM_ADMIN 短絡・親の不在コード）の検証を、認可の移設先である Facade へ移したもの。
 * ゲートは実物を {@link AccessControlService} のモックの上に組む。応答の全体は
 * {@code ShiftScheduleSlotFacadeContractIT} が実 DB で固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftSlotFacade 認可 単体テスト")
class ShiftSlotFacadeTest {

    private static final Long TEAM_ID = 1L;
    private static final Long SCHEDULE_ID = 100L;
    private static final Long SLOT_ID = 200L;
    private static final Long USER_ID = 10L;

    @Mock
    private ShiftSlotService slotService;
    @Mock
    private AccessControlService accessControlService;

    private ShiftSlotFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ShiftSlotFacade(slotService, new ScopeConcealingAccessGate(accessControlService),
                accessControlService);
    }

    private static ShiftScheduleScope published() {
        return new ShiftScheduleScope(SCHEDULE_ID, TEAM_ID, ShiftScheduleStatus.PUBLISHED,
                true);
    }

    private static void assertCode(Throwable thrown, ErrorCode expected) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(expected);
    }

    private static CreateShiftSlotRequest createReq() {
        return new CreateShiftSlotRequest(
                LocalDate.of(2026, 3, 2), LocalTime.of(9, 0), LocalTime.of(17, 0), null, 1, null);
    }

    // ========================================
    // 枠一覧
    // ========================================

    @Nested
    @DisplayName("listSlots")
    class ListSlots {

        @Test
        @DisplayName("越境は不在と同じ 404 SHIFT_001")
        void 越境は404() {
            given(slotService.resolveScheduleScope(SCHEDULE_ID)).willReturn(published());
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertCode(catchThrowable(() -> facade.listSlots(SCHEDULE_ID, USER_ID)),
                    ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            verify(slotService, never()).listSlots(anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("SUPPORTER は 403 COMMON_002")
        void SUPPORTERは403() {
            given(slotService.resolveScheduleScope(SCHEDULE_ID)).willReturn(published());
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isSupporter(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertCode(catchThrowable(() -> facade.listSlots(SCHEDULE_ID, USER_ID)), CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("未公開は認可結果より先に 404（SUPPORTER にも 403 を見せない）")
        void 未公開は404() {
            given(slotService.resolveScheduleScope(SCHEDULE_ID)).willReturn(
                    new ShiftScheduleScope(SCHEDULE_ID, TEAM_ID, ShiftScheduleStatus.DRAFT, false));

            assertCode(catchThrowable(() -> facade.listSlots(SCHEDULE_ID, USER_ID)),
                    ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        }

        @Test
        @DisplayName("一般メンバーは privileged=false で一覧を取る")
        void 一般メンバーは通る() {
            List<ShiftSlotResponse> slots = List.of();
            given(slotService.resolveScheduleScope(SCHEDULE_ID)).willReturn(published());
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(slotService.listSlots(SCHEDULE_ID, false)).willReturn(slots);

            assertThat(facade.listSlots(SCHEDULE_ID, USER_ID)).isSameAs(slots);
        }

        @Test
        @DisplayName("user_roles のみの ADMIN は在籍を問わず通る（退行させない）")
        void userRolesのみのADMINは通る() {
            List<ShiftSlotResponse> slots = List.of();
            given(slotService.resolveScheduleScope(SCHEDULE_ID)).willReturn(published());
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(slotService.listSlots(SCHEDULE_ID, true)).willReturn(slots);

            assertThat(facade.listSlots(SCHEDULE_ID, USER_ID)).isSameAs(slots);
            verify(accessControlService, never()).isMember(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は短絡して通る")
        void SYSTEM_ADMINは短絡() {
            List<ShiftSlotResponse> slots = List.of();
            given(slotService.resolveScheduleScope(SCHEDULE_ID)).willReturn(published());
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            given(slotService.listSlots(SCHEDULE_ID, true)).willReturn(slots);

            assertThat(facade.listSlots(SCHEDULE_ID, USER_ID)).isSameAs(slots);
        }
    }

    // ========================================
    // スケジュール起点の書き込み（作成・一括作成）
    // ========================================

    @Nested
    @DisplayName("createSlot・bulkCreateSlots（スケジュール起点）")
    class ScheduleOriginWrites {

        private final CreateShiftSlotRequest req = createReq();

        @BeforeEach
        void scope() {
            given(slotService.resolveScheduleScope(SCHEDULE_ID)).willReturn(published());
        }

        @Test
        @DisplayName("越境は不在と同じ 404 SHIFT_001 で tx 本体を呼ばない")
        void 越境は404() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            BulkCreateShiftSlotRequest bulk = new BulkCreateShiftSlotRequest(List.of(req));

            assertCode(catchThrowable(() -> facade.createSlot(SCHEDULE_ID, req, USER_ID)),
                    ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            assertCode(catchThrowable(() -> facade.bulkCreateSlots(SCHEDULE_ID, bulk, USER_ID)),
                    ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            verify(slotService, never()).createSlot(anyLong(), any());
            verify(slotService, never()).bulkCreateSlots(anyLong(), any());
        }

        @Test
        @DisplayName("同チームの一般メンバーは 403 COMMON_002")
        void 権限不足は403() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertCode(catchThrowable(() -> facade.createSlot(SCHEDULE_ID, req, USER_ID)),
                    CommonErrorCode.COMMON_002);
            verify(slotService, never()).createSlot(anyLong(), any());
        }

        @Test
        @DisplayName("管理者・SYSTEM_ADMIN は tx 本体へ進む")
        void 管理者は通る() {
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            ShiftSlotResponse created = ShiftSlotResponse.builder().id(SLOT_ID).build();
            given(slotService.createSlot(SCHEDULE_ID, req)).willReturn(created);

            assertThat(facade.createSlot(SCHEDULE_ID, req, USER_ID)).isSameAs(created);
        }
    }

    // ========================================
    // 枠起点の書き込み（更新・割当・削除）: 越境も親不在も SHIFT_002
    // ========================================

    @Nested
    @DisplayName("updateSlot・patchSlotAssignments・deleteSlot（枠起点）")
    class SlotOriginWrites {

        private final UpdateShiftSlotRequest updateReq =
                new UpdateShiftSlotRequest(null, null, null, null, null, null, "メモ");
        private final SlotAssignmentPatchRequest patchReq =
                new SlotAssignmentPatchRequest(List.of(1L), List.of(), 0);

        @Test
        @DisplayName("越境は不在と同じ 404 SHIFT_002 で tx 本体を呼ばない")
        void 越境は404() {
            given(slotService.resolveSlotTeamId(SLOT_ID)).willReturn(TEAM_ID);
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertCode(catchThrowable(() -> facade.updateSlot(SLOT_ID, updateReq, USER_ID)),
                    ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
            assertCode(catchThrowable(() -> facade.patchSlotAssignments(SLOT_ID, patchReq, USER_ID)),
                    ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
            assertCode(catchThrowable(() -> facade.deleteSlot(SLOT_ID, USER_ID)),
                    ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
            verify(slotService, never()).updateSlot(anyLong(), any(), anyLong());
            verify(slotService, never()).patchSlotAssignments(anyLong(), any(), anyLong());
            verify(slotService, never()).deleteSlot(anyLong());
        }

        @Test
        @DisplayName("同チームの一般メンバーは 403 COMMON_002")
        void 権限不足は403() {
            given(slotService.resolveSlotTeamId(SLOT_ID)).willReturn(TEAM_ID);
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertCode(catchThrowable(() -> facade.deleteSlot(SLOT_ID, USER_ID)), CommonErrorCode.COMMON_002);
            verify(slotService, never()).deleteSlot(anyLong());
        }

        @Test
        @DisplayName("枠・親の不在（resolveSlotTeamId が SHIFT_002）は認可に進まない")
        void 不在は認可に進まない() {
            given(slotService.resolveSlotTeamId(SLOT_ID))
                    .willThrow(new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND));

            assertCode(catchThrowable(() -> facade.updateSlot(SLOT_ID, updateReq, USER_ID)),
                    ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
            verify(accessControlService, never()).isAdminOrAbove(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("管理者・SYSTEM_ADMIN は tx 本体へ進む")
        void 管理者は通る() {
            given(slotService.resolveSlotTeamId(SLOT_ID)).willReturn(TEAM_ID);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            ShiftSlotResponse updated = ShiftSlotResponse.builder().id(SLOT_ID).build();
            given(slotService.updateSlot(SLOT_ID, updateReq, USER_ID)).willReturn(updated);
            given(slotService.patchSlotAssignments(SLOT_ID, patchReq, USER_ID)).willReturn(updated);

            assertThat(facade.updateSlot(SLOT_ID, updateReq, USER_ID)).isSameAs(updated);
            assertThat(facade.patchSlotAssignments(SLOT_ID, patchReq, USER_ID)).isSameAs(updated);
            facade.deleteSlot(SLOT_ID, USER_ID);
            verify(slotService).deleteSlot(SLOT_ID);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は短絡して通る（チーム判定を撃たない）")
        void SYSTEM_ADMINは短絡() {
            given(slotService.resolveSlotTeamId(SLOT_ID)).willReturn(TEAM_ID);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            facade.deleteSlot(SLOT_ID, USER_ID);

            verify(slotService).deleteSlot(SLOT_ID);
            verify(accessControlService, never()).isAdminOrAbove(anyLong(), anyLong(), any());
            verify(accessControlService, never()).isMember(anyLong(), anyLong(), any());
        }
    }
}
