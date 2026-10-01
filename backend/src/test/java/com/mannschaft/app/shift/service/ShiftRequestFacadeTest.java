package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.CreateShiftRequestRequest;
import com.mannschaft.app.shift.dto.UpdateShiftRequestRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftRequestFacade} の単体テスト（CMP-260923-0954 W1 の作り替え）。
 * 「scope 解決 → 認可 → tx 本体」の順序と、認可で拒否したら tx 本体を呼ばないことを固定する。
 * 認可の成否そのもの（主体 × 結果）は {@code ShiftRequestPositionScopeContractIT} が応答で固定する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftRequestFacade 単体テスト")
class ShiftRequestFacadeTest {

    private static final Long SCHEDULE_ID = 100L;
    private static final Long REQUEST_ID = 300L;
    private static final Long TEAM_ID = 1L;
    private static final Long OWNER_ID = 10L;
    private static final Long USER_ID = 11L;

    @Mock
    private ShiftRequestService requestService;

    @Mock
    private ScopeConcealingAccessGate accessGate;

    @Mock
    private RoleService roleService;

    @InjectMocks
    private ShiftRequestFacade facade;

    @Test
    @DisplayName("一覧_scope解決→管理者認可→tx本体の順")
    void 一覧_順序() {
        given(requestService.resolveScheduleScope(SCHEDULE_ID))
                .willReturn(new ShiftRequestService.ScheduleScope(TEAM_ID));

        facade.listRequests(SCHEDULE_ID, USER_ID);

        InOrder order = inOrder(requestService, accessGate);
        order.verify(requestService).resolveScheduleScope(SCHEDULE_ID);
        order.verify(accessGate).requireAdminOrConceal(USER_ID, TEAM_ID, "TEAM",
                ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        order.verify(requestService).listRequests(SCHEDULE_ID);
    }

    @Test
    @DisplayName("一覧_認可拒否_tx本体を呼ばない")
    void 一覧_拒否() {
        given(requestService.resolveScheduleScope(SCHEDULE_ID))
                .willReturn(new ShiftRequestService.ScheduleScope(TEAM_ID));
        doThrow(new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND)).when(accessGate)
                .requireAdminOrConceal(any(), any(), any(), any());

        assertThatThrownBy(() -> facade.listRequests(SCHEDULE_ID, USER_ID)).isInstanceOf(BusinessException.class);
        verify(requestService, never()).listRequests(any());
    }

    @Test
    @DisplayName("サマリー_scope解決→管理者認可→tx本体の順")
    void サマリー_順序() {
        given(requestService.resolveScheduleScope(SCHEDULE_ID))
                .willReturn(new ShiftRequestService.ScheduleScope(TEAM_ID));

        List<Long> candidates = List.of(5L, 6L);
        given(roleService.getMemberCandidateUserIdsByTeamId(TEAM_ID)).willReturn(candidates);

        facade.getRequestSummary(SCHEDULE_ID, USER_ID);

        InOrder order = inOrder(requestService, accessGate, roleService);
        order.verify(requestService).resolveScheduleScope(SCHEDULE_ID);
        order.verify(accessGate).requireAdminOrConceal(USER_ID, TEAM_ID, "TEAM",
                ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        order.verify(roleService).getMemberCandidateUserIdsByTeamId(TEAM_ID);
        order.verify(requestService).getRequestSummary(SCHEDULE_ID, candidates);
    }

    @Test
    @DisplayName("提出_scope解決→メンバー認可(SUPPORTER不可)→tx本体の順")
    void 提出_順序() {
        CreateShiftRequestRequest req = new CreateShiftRequestRequest(
                SCHEDULE_ID, null, LocalDate.of(2026, 3, 2), "PREFERRED", null);
        given(requestService.resolveScheduleScope(SCHEDULE_ID))
                .willReturn(new ShiftRequestService.ScheduleScope(TEAM_ID));

        facade.submitRequest(req, USER_ID);

        InOrder order = inOrder(requestService, accessGate);
        order.verify(requestService).resolveScheduleScope(SCHEDULE_ID);
        order.verify(accessGate).requireMemberOrConceal(USER_ID, TEAM_ID, "TEAM",
                ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, true);
        order.verify(requestService).submitRequest(req, USER_ID);
    }

    @Test
    @DisplayName("提出_認可拒否_tx本体（親行ロック・保存）に入らない")
    void 提出_拒否() {
        CreateShiftRequestRequest req = new CreateShiftRequestRequest(
                SCHEDULE_ID, null, LocalDate.of(2026, 3, 2), "PREFERRED", null);
        given(requestService.resolveScheduleScope(SCHEDULE_ID))
                .willReturn(new ShiftRequestService.ScheduleScope(TEAM_ID));
        doThrow(new BusinessException(CommonErrorCode.COMMON_002)).when(accessGate)
                .requireMemberOrConceal(any(), any(), any(), any(), anyBoolean());

        assertThatThrownBy(() -> facade.submitRequest(req, USER_ID)).isInstanceOf(BusinessException.class);
        verify(requestService, never()).submitRequest(any(), any());
    }

    @Test
    @DisplayName("更新_scope解決→本人または管理者の認可(提出者を渡す)→tx本体の順")
    void 更新_順序() {
        UpdateShiftRequestRequest req = new UpdateShiftRequestRequest("AVAILABLE", null);
        given(requestService.resolveRequestScope(REQUEST_ID))
                .willReturn(new ShiftRequestService.RequestScope(TEAM_ID, OWNER_ID));

        facade.updateRequest(REQUEST_ID, req, USER_ID);

        InOrder order = inOrder(requestService, accessGate);
        order.verify(requestService).resolveRequestScope(REQUEST_ID);
        order.verify(accessGate).requireOwnerOrAdminOrConceal(USER_ID, TEAM_ID, "TEAM", OWNER_ID,
                ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND);
        order.verify(requestService).updateRequest(REQUEST_ID, req);
    }

    @Test
    @DisplayName("削除_scope解決→本人または管理者の認可(提出者を渡す)→tx本体の順")
    void 削除_順序() {
        given(requestService.resolveRequestScope(REQUEST_ID))
                .willReturn(new ShiftRequestService.RequestScope(TEAM_ID, OWNER_ID));

        facade.deleteRequest(REQUEST_ID, USER_ID);

        InOrder order = inOrder(requestService, accessGate);
        order.verify(requestService).resolveRequestScope(REQUEST_ID);
        order.verify(accessGate).requireOwnerOrAdminOrConceal(USER_ID, TEAM_ID, "TEAM", OWNER_ID,
                ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND);
        order.verify(requestService).deleteRequest(REQUEST_ID);
    }

    @Test
    @DisplayName("更新・削除_認可拒否_tx本体を呼ばない")
    void 更新削除_拒否() {
        given(requestService.resolveRequestScope(REQUEST_ID))
                .willReturn(new ShiftRequestService.RequestScope(TEAM_ID, OWNER_ID));
        doThrow(new BusinessException(ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND)).when(accessGate)
                .requireOwnerOrAdminOrConceal(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> facade.updateRequest(REQUEST_ID,
                new UpdateShiftRequestRequest("AVAILABLE", null), USER_ID)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> facade.deleteRequest(REQUEST_ID, USER_ID)).isInstanceOf(BusinessException.class);
        verify(requestService, never()).updateRequest(any(), any());
        verify(requestService, never()).deleteRequest(any());
    }
}
