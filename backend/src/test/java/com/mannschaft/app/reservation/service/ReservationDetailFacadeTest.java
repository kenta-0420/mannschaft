package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationDetailFacade} の単体テスト（CMP-260923-0954 W3a）。
 * 認可の応答（status・code・message）が是正前の #3544 の契約と一致することを固定する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationDetailFacade 単体テスト")
class ReservationDetailFacadeTest {

    private static final Long TEAM_ID = 1L;
    private static final Long RESERVATION_ID = 10L;
    private static final Long OWNER_ID = 100L;
    private static final Long OTHER_ID = 200L;
    private static final Long ADMIN_ID = 300L;

    @Mock
    private ReservationService reservationService;

    @Mock
    private AccessControlService accessControlService;

    private ReservationDetailFacade facade() {
        return new ReservationDetailFacade(reservationService, accessControlService);
    }

    private <T> T asUser(Long userId, java.util.function.Supplier<T> body) {
        try (MockedStatic<SecurityUtils> mocked = Mockito.mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(userId);
            return body.get();
        }
    }

    private void assertError(Throwable t, ReservationErrorCode expected) {
        assertThat(t).isInstanceOf(BusinessException.class);
        BusinessException e = (BusinessException) t;
        assertThat(e.getErrorCode()).isEqualTo(expected);
        assertThat(e.getErrorCode().getCode()).isEqualTo(expected.getCode());
        assertThat(e.getErrorCode().getMessage()).isEqualTo(expected.getMessage());
    }

    @Test
    @DisplayName("管理者（user_roles のみの管理者を含む）は許可され本体を呼ぶ")
    void 管理者は許可() {
        ReservationResponse response = ReservationResponse.builder().id(RESERVATION_ID).build();
        given(reservationService.resolveOwnerUserId(TEAM_ID, RESERVATION_ID)).willReturn(OWNER_ID);
        given(accessControlService.isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(reservationService.getReservation(TEAM_ID, RESERVATION_ID)).willReturn(response);

        ReservationResponse result = asUser(ADMIN_ID, () -> facade().getReservation(TEAM_ID, RESERVATION_ID));

        assertThat(result).isSameAs(response);
        verify(accessControlService, never()).isMember(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
        verify(accessControlService, never()).isSystemAdmin(Mockito.anyLong());
    }

    @Test
    @DisplayName("本人は許可（拒否経路の isMember / isSystemAdmin を引かない）")
    void 本人は許可() {
        ReservationResponse response = ReservationResponse.builder().id(RESERVATION_ID).build();
        given(reservationService.resolveOwnerUserId(TEAM_ID, RESERVATION_ID)).willReturn(OWNER_ID);
        given(accessControlService.isAdminOrAbove(OWNER_ID, TEAM_ID, "TEAM")).willReturn(false);
        given(reservationService.getReservation(TEAM_ID, RESERVATION_ID)).willReturn(response);

        ReservationResponse result = asUser(OWNER_ID, () -> facade().getReservation(TEAM_ID, RESERVATION_ID));

        assertThat(result).isSameAs(response);
        verify(accessControlService, never()).isMember(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
        verify(accessControlService, never()).isSystemAdmin(Mockito.anyLong());
    }

    @Test
    @DisplayName("同チームの一般メンバー（他人の予約）・メンバーの SYSTEM_ADMIN は 403 RESERVATION_021、本体は呼ばない")
    void 同チームの閲覧不可は403() {
        given(reservationService.resolveOwnerUserId(TEAM_ID, RESERVATION_ID)).willReturn(OWNER_ID);
        given(accessControlService.isAdminOrAbove(OTHER_ID, TEAM_ID, "TEAM")).willReturn(false);
        given(accessControlService.isMember(OTHER_ID, TEAM_ID, "TEAM")).willReturn(true);

        assertThatThrownBy(() -> asUser(OTHER_ID, () -> facade().getReservation(TEAM_ID, RESERVATION_ID)))
                .satisfies(t -> assertError(t, ReservationErrorCode.RESERVATION_PERMISSION_DENIED));
        verify(reservationService, never()).getReservation(TEAM_ID, RESERVATION_ID);
    }

    @Test
    @DisplayName("非メンバーの SYSTEM_ADMIN は 403 RESERVATION_021（是正前から拒否・新規に許可も 404 化もしない）")
    void 非メンバーのSYSTEM_ADMINは403() {
        given(reservationService.resolveOwnerUserId(TEAM_ID, RESERVATION_ID)).willReturn(OWNER_ID);
        given(accessControlService.isAdminOrAbove(OTHER_ID, TEAM_ID, "TEAM")).willReturn(false);
        given(accessControlService.isMember(OTHER_ID, TEAM_ID, "TEAM")).willReturn(false);
        given(accessControlService.isSystemAdmin(OTHER_ID)).willReturn(true);

        assertThatThrownBy(() -> asUser(OTHER_ID, () -> facade().getReservation(TEAM_ID, RESERVATION_ID)))
                .satisfies(t -> assertError(t, ReservationErrorCode.RESERVATION_PERMISSION_DENIED));
    }

    @Test
    @DisplayName("部外者（越境）は 404 RESERVATION_003（不在と同一の code・message）")
    void 部外者は404() {
        given(reservationService.resolveOwnerUserId(TEAM_ID, RESERVATION_ID)).willReturn(OWNER_ID);
        given(accessControlService.isAdminOrAbove(OTHER_ID, TEAM_ID, "TEAM")).willReturn(false);
        given(accessControlService.isMember(OTHER_ID, TEAM_ID, "TEAM")).willReturn(false);
        given(accessControlService.isSystemAdmin(OTHER_ID)).willReturn(false);

        assertThatThrownBy(() -> asUser(OTHER_ID, () -> facade().getReservation(TEAM_ID, RESERVATION_ID)))
                .satisfies(t -> assertError(t, ReservationErrorCode.RESERVATION_NOT_FOUND));
        verify(reservationService, never()).getReservation(TEAM_ID, RESERVATION_ID);
    }

    @Test
    @DisplayName("対象不在・論理削除済みは認可に到達せず 404 RESERVATION_003")
    void 対象不在は認可前に404() {
        given(reservationService.resolveOwnerUserId(TEAM_ID, RESERVATION_ID))
                .willThrow(new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));

        assertThatThrownBy(() -> asUser(OTHER_ID, () -> facade().getReservation(TEAM_ID, RESERVATION_ID)))
                .satisfies(t -> assertError(t, ReservationErrorCode.RESERVATION_NOT_FOUND));
        Mockito.verifyNoInteractions(accessControlService);
    }

    @Test
    @DisplayName("競合（K1）: 認可の後に予約が論理削除されたら、読み直しで不在と同じ 404 RESERVATION_003")
    void 認可の後に論理削除された場合は404() {
        given(reservationService.resolveOwnerUserId(TEAM_ID, RESERVATION_ID)).willReturn(OWNER_ID);
        given(accessControlService.isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM")).willReturn(true);
        // 認可の後・本体 tx の前に論理削除された状況: 読み直しが不在になる
        given(reservationService.getReservation(TEAM_ID, RESERVATION_ID))
                .willThrow(new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));

        assertThatThrownBy(() -> asUser(ADMIN_ID, () -> facade().getReservation(TEAM_ID, RESERVATION_ID)))
                .satisfies(t -> assertError(t, ReservationErrorCode.RESERVATION_NOT_FOUND));

        InOrder order = inOrder(reservationService, accessControlService);
        order.verify(reservationService).resolveOwnerUserId(TEAM_ID, RESERVATION_ID);
        order.verify(accessControlService).isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM");
        order.verify(reservationService).getReservation(TEAM_ID, RESERVATION_ID);
    }
}
