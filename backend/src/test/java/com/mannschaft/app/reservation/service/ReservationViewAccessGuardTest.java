package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.ReservationErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationViewAccessGuard} の単体テスト（§4.C 認可・予約作成と共有する view 述語）。
 *
 * <p>予約閲覧可否は「会員 or {@code allow_public_reservation}」。ADMIN 限定にしないこと（C-7）を担保する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationViewAccessGuard 単体テスト（予約閲覧 view ゲート）")
class ReservationViewAccessGuardTest {

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 100L;

    @Mock
    private ReservationTeamSettingService settingService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private ReservationViewAccessGuard guard;

    @Test
    @DisplayName("C-7: 非公開でも当該チーム会員なら通過（ADMIN 限定でない・一般会員が可）")
    void 会員は通過() {
        given(settingService.isAllowPublic(TEAM_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

        assertThatCode(() -> guard.assertCanView(TEAM_ID, USER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("C-7: 公開ON なら非会員でも通過（所属チェックを短絡でスキップ）")
    void 公開ONは非会員でも通過() {
        given(settingService.isAllowPublic(TEAM_ID)).willReturn(true);

        assertThatCode(() -> guard.assertCanView(TEAM_ID, USER_ID)).doesNotThrowAnyException();

        // 公開ON のときは短絡評価で isMember を呼ばない。
        verify(accessControlService, never()).isMember(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("C-6: 非会員かつ非公開は RESERVATION_PERMISSION_DENIED（403 相当）")
    void 非会員かつ非公開は拒否() {
        given(settingService.isAllowPublic(TEAM_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        assertThatThrownBy(() -> guard.assertCanView(TEAM_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);
    }

    @Test
    @DisplayName("エラーコードの HTTP マッピングは 403（GlobalExceptionHandler の個別上書き）")
    void エラーコードは403相当() {
        // RESERVATION_021 は Severity.WARN だが GlobalExceptionHandler で 403 に上書きされる契約。
        assertThat(ReservationErrorCode.RESERVATION_PERMISSION_DENIED.getCode()).isEqualTo("RESERVATION_021");
    }
}
