package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * {@link ConfirmableScopeAuthorizer} の単体テスト（CMP-260923-0954 W3b）。
 * 越境（NOT_FOUND）と関係者の権限不足（403）の作り分けと、許可経路で余計な判定を引かないことを固定する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmableScopeAuthorizer 単体テスト")
class ConfirmableScopeAuthorizerTest {

    private static final Long USER = 1L;
    private static final Long SCOPE = 10L;
    private static final ConfirmableNotificationErrorCode NF = ConfirmableNotificationErrorCode.NOT_FOUND;

    @Mock
    private AccessControlService acs;

    @InjectMocks
    private ConfirmableScopeAuthorizer authorizer;

    private static void assertCode(Throwable t, Object code) {
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getErrorCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("requireMember: 在籍者は通り、許可経路で SYSTEM_ADMIN・ADMIN の判定を引かない")
    void 在籍者は通る() {
        given(acs.isMember(USER, SCOPE, "TEAM")).willReturn(true);

        assertThatCode(() -> authorizer.requireMember(USER, ScopeType.TEAM, SCOPE, NF)).doesNotThrowAnyException();

        verify(acs).isMember(USER, SCOPE, "TEAM");
        verifyNoMoreInteractions(acs);
    }

    @Test
    @DisplayName("requireMember: 無関係な者は notFoundCode（不在IDと同じコード）")
    void 無関係な者は不在コード() {
        assertThatThrownBy(() -> authorizer.requireMember(USER, ScopeType.TEAM, SCOPE, NF))
                .satisfies(t -> assertCode(t, NF));
    }

    @Test
    @DisplayName("requireMember: user_roles のみの ADMIN は関係者として 403（404 に化けない）")
    void userRolesのみのADMINは403() {
        given(acs.isAdminOrAbove(USER, SCOPE, "TEAM")).willReturn(true);

        assertThatThrownBy(() -> authorizer.requireMember(USER, ScopeType.TEAM, SCOPE, NF))
                .satisfies(t -> assertCode(t, CommonErrorCode.COMMON_002));
    }

    @Test
    @DisplayName("requireMember: SYSTEM_ADMIN（非メンバー）は是正前どおり 403")
    void SYSTEM_ADMINは403() {
        given(acs.isSystemAdmin(USER)).willReturn(true);

        assertThatThrownBy(() -> authorizer.requireMember(USER, ScopeType.TEAM, SCOPE, NF))
                .satisfies(t -> assertCode(t, CommonErrorCode.COMMON_002));
    }

    @Test
    @DisplayName("requireSendPermission: ADMIN / SEND_NOTIFICATION を持つ DEPUTY_ADMIN は通り、判定は1回だけ")
    void 送信権限があれば通る() {
        given(acs.hasAdminOrPermissionInScope(USER, SCOPE, "ORGANIZATION", "SEND_NOTIFICATION")).willReturn(true);

        assertThatCode(() -> authorizer.requireSendPermission(USER, ScopeType.ORGANIZATION, SCOPE, NF))
                .doesNotThrowAnyException();

        verify(acs).hasAdminOrPermissionInScope(USER, SCOPE, "ORGANIZATION", "SEND_NOTIFICATION");
        verifyNoMoreInteractions(acs);
    }

    @Test
    @DisplayName("requireSendPermission: 同スコープの在籍者で権限が無ければ 403")
    void 同スコープの権限不足は403() {
        given(acs.isMember(USER, SCOPE, "TEAM")).willReturn(true);

        assertThatThrownBy(() -> authorizer.requireSendPermission(USER, ScopeType.TEAM, SCOPE, NF))
                .satisfies(t -> assertCode(t, CommonErrorCode.COMMON_002));
    }

    @Test
    @DisplayName("requireSendPermission: 無関係な者は notFoundCode（テンプレートなら TEMPLATE_NOT_FOUND を渡す）")
    void 無関係な者は不在コード_送信権限() {
        assertThatThrownBy(() -> authorizer.requireSendPermission(
                USER, ScopeType.TEAM, SCOPE, ConfirmableNotificationErrorCode.TEMPLATE_NOT_FOUND))
                .satisfies(t -> assertCode(t, ConfirmableNotificationErrorCode.TEMPLATE_NOT_FOUND));
    }
}
