package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * {@link SignageScreenService#listScreensForActor} の認可単体テスト（CMP-260917-1350 Phase 1）。
 *
 * <p>組織サイドバーで ADMIN/DEPUTY_ADMIN 限定表示している「サイネージ」機能の GET が
 * {@code checkMembership} 止まりで MEMBER も閲覧できていた認可漏れを根治する。ただし
 * <b>ORGANIZATION スコープのみ</b> ADMIN 必須とし、<b>TEAM スコープは
 * {@code SignageScopeContractIT} が過去戦役 Wave7 で固定した契約（一般メンバー(非ADMIN)の
 * 画面一覧取得は200）を維持する</b>。本テストはスコープ差分を番人として固定する
 * （2026-09-17: 最初の是正がスコープを区別せず TEAM 側の契約を巻き込んで CI を赤化させた）。
 * サイネージ端末が無記名で表示する {@code listScreens}（signage_access_tokens 経由）は
 * 本テストの対象外であり、そちらは認可なしのまま維持する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignageScreenService 認可単体テスト（listScreensForActor・スコープ差分）")
class SignageScreenServiceListScreensForActorAuthzTest {

    @Mock private SignageScreenRepository screenRepository;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private SignageScreenService screenService;

    private static final Long SCOPE_ID = 1L;
    private static final Long ACTOR_ID = 100L;

    @Test
    @DisplayName("ORGANIZATIONスコープ: MEMBERは403（COMMON_002）で拒否される")
    void organization_member_isForbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkAdminOrAbove(ACTOR_ID, SCOPE_ID, "ORGANIZATION");

        assertThatThrownBy(() -> screenService.listScreensForActor("ORGANIZATION", SCOPE_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("ORGANIZATIONスコープ: ADMINは200相当で取得できる")
    void organization_admin_isAllowed() {
        doNothing().when(accessControlService).checkAdminOrAbove(ACTOR_ID, SCOPE_ID, "ORGANIZATION");
        given(screenRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull("ORGANIZATION", SCOPE_ID))
                .willReturn(List.of());

        assertThatCode(() -> screenService.listScreensForActor("ORGANIZATION", SCOPE_ID, ACTOR_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TEAMスコープ: MEMBERは200相当で取得できる（Wave7契約維持）")
    void team_member_isAllowed() {
        doNothing().when(accessControlService).checkMembership(ACTOR_ID, SCOPE_ID, "TEAM");
        given(screenRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull("TEAM", SCOPE_ID))
                .willReturn(List.of());

        assertThatCode(() -> screenService.listScreensForActor("TEAM", SCOPE_ID, ACTOR_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TEAMスコープ: 非メンバーは403（COMMON_002）で拒否される")
    void team_nonMember_isForbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkMembership(ACTOR_ID, SCOPE_ID, "TEAM");

        assertThatThrownBy(() -> screenService.listScreensForActor("TEAM", SCOPE_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }
}
