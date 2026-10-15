package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.CreatePositionRequest;
import com.mannschaft.app.shift.dto.UpdatePositionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftPositionFacade} の単体テスト（CMP-260923-0954 W1 の作り替え）。
 * 順序（scope 解決 → 認可 → tx 本体）と、認可で拒否したら tx 本体を呼ばないことを固定する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftPositionFacade 単体テスト")
class ShiftPositionFacadeTest {

    private static final Long TEAM_ID = 1L;
    private static final Long POSITION_ID = 50L;
    private static final Long USER_ID = 900L;

    @Mock
    private ShiftPositionService positionService;

    @Mock
    private ScopeConcealingAccessGate accessGate;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private ShiftPositionFacade facade;

    @Test
    @DisplayName("更新_scope解決→管理者認可→tx本体の順")
    void 更新_順序() {
        UpdatePositionRequest req = new UpdatePositionRequest("ホール", null, null);
        given(positionService.resolvePositionScope(POSITION_ID))
                .willReturn(new ShiftPositionService.PositionScope(TEAM_ID));

        facade.updatePosition(POSITION_ID, req, USER_ID);

        InOrder order = inOrder(positionService, accessGate);
        order.verify(positionService).resolvePositionScope(POSITION_ID);
        order.verify(accessGate).requireAdminOrConceal(USER_ID, TEAM_ID, "TEAM",
                ShiftErrorCode.SHIFT_POSITION_NOT_FOUND);
        order.verify(positionService).updatePosition(POSITION_ID, req);
    }

    @Test
    @DisplayName("削除_scope解決→管理者認可→tx本体の順、認可拒否ならtx本体を呼ばない")
    void 削除_順序と拒否() {
        given(positionService.resolvePositionScope(POSITION_ID))
                .willReturn(new ShiftPositionService.PositionScope(TEAM_ID));

        facade.deletePosition(POSITION_ID, USER_ID);

        InOrder order = inOrder(positionService, accessGate);
        order.verify(positionService).resolvePositionScope(POSITION_ID);
        order.verify(accessGate).requireAdminOrConceal(USER_ID, TEAM_ID, "TEAM",
                ShiftErrorCode.SHIFT_POSITION_NOT_FOUND);
        order.verify(positionService).deletePosition(POSITION_ID);

        org.mockito.Mockito.clearInvocations(positionService);
        doThrow(new BusinessException(ShiftErrorCode.SHIFT_POSITION_NOT_FOUND)).when(accessGate)
                .requireAdminOrConceal(any(), any(), any(), any());
        assertThatThrownBy(() -> facade.deletePosition(POSITION_ID, USER_ID)).isInstanceOf(BusinessException.class);
        verify(positionService, never()).deletePosition(any());
    }

    @Test
    @DisplayName("作成_SYSTEM_ADMINは短絡で通る／それ以外は管理者確認の後にtx本体")
    void 作成_認可() {
        CreatePositionRequest req = new CreatePositionRequest("ホール", 2);
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
        facade.createPosition(TEAM_ID, req, USER_ID);
        verify(accessControlService, never()).checkAdminOrAbove(any(), any(), any());
        verify(positionService).createPosition(TEAM_ID, req);

        org.mockito.Mockito.clearInvocations(positionService);
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        doThrow(new BusinessException(CommonErrorCode.COMMON_002)).when(accessControlService)
                .checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
        assertThatThrownBy(() -> facade.createPosition(TEAM_ID, req, USER_ID)).isInstanceOf(BusinessException.class);
        verify(positionService, never()).createPosition(any(), any());
    }

    @Test
    @DisplayName("一覧_非メンバー・SUPPORTERは403 COMMON_002でtx本体を呼ばない／メンバーは通る")
    void 一覧_認可() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false, true, true);
        given(accessControlService.isSupporter(USER_ID, TEAM_ID, "TEAM")).willReturn(true, false);

        // 非メンバー
        assertThatThrownBy(() -> facade.listPositions(TEAM_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(t -> assertThat(((BusinessException) t).getErrorCode())
                        .isEqualTo(CommonErrorCode.COMMON_002));
        // メンバーだが SUPPORTER
        assertThatThrownBy(() -> facade.listPositions(TEAM_ID, USER_ID)).isInstanceOf(BusinessException.class);
        verify(positionService, never()).listPositions(any());
        // 一般メンバー
        facade.listPositions(TEAM_ID, USER_ID);
        verify(positionService).listPositions(TEAM_ID);
    }
}
