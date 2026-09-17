package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.signage.SignageLayout;
import com.mannschaft.app.signage.SignageTransitionEffect;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
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
 * {@code checkMembership} 止まりで MEMBER も閲覧できていた認可漏れを根治する。
 * サイネージ端末が無記名で表示する {@code listScreens}（signage_access_tokens 経由）は
 * 本テストの対象外であり、そちらは認可なしのまま維持する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignageScreenService 認可単体テスト（listScreensForActor）")
class SignageScreenServiceListScreensForActorAuthzTest {

    @Mock private SignageScreenRepository screenRepository;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private SignageScreenService screenService;

    private static final Long SCOPE_ID = 1L;
    private static final String SCOPE_TYPE = "TEAM";
    private static final Long ACTOR_ID = 100L;

    @Test
    @DisplayName("MEMBER は 403（COMMON_002）で拒否される")
    void member_isForbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkAdminOrAbove(ACTOR_ID, SCOPE_ID, SCOPE_TYPE);

        assertThatThrownBy(() -> screenService.listScreensForActor(SCOPE_TYPE, SCOPE_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("ADMIN は 200 相当で取得できる")
    void admin_isAllowed() {
        doNothing().when(accessControlService).checkAdminOrAbove(ACTOR_ID, SCOPE_ID, SCOPE_TYPE);
        given(screenRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(SCOPE_TYPE, SCOPE_ID))
                .willReturn(List.of());

        assertThatCode(() -> screenService.listScreensForActor(SCOPE_TYPE, SCOPE_ID, ACTOR_ID))
                .doesNotThrowAnyException();
    }
}
