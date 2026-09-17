package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.TeamOrgAnalyticsErrorCode;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link PageViewAnalyticsAccessGuard#requireScopeMember} の認可単体テスト
 * （CMP-260917-1350 Phase 1）。
 *
 * <p>組織サイドバーで ADMIN/DEPUTY_ADMIN 限定表示している「分析」機能の GET が、
 * 旧仕様（メンバーなら閲覧可）のままだった認可漏れを根治する。是正後は
 * ADMIN/DEPUTY_ADMIN（および SYSTEM_ADMIN）のみ許可し、それ以外は非メンバーと同様に
 * 404（{@code TEAMANALYTICS_001}）で存在を秘匿する（IDOR 隠蔽の設計方針は維持）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PageViewAnalyticsAccessGuard 認可単体テスト（requireScopeMember）")
class PageViewAnalyticsAccessGuardTest {

    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private PageViewAnalyticsAccessGuard guard;

    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 100L;

    @Test
    @DisplayName("MEMBER は 404（TEAMANALYTICS_001）で拒否される")
    void member_isForbidden() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM")).willReturn(false);

        assertThatThrownBy(() -> guard.requireScopeMember(USER_ID, PageViewScopeType.TEAM, SCOPE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TeamOrgAnalyticsErrorCode.TEAMANALYTICS_001);
    }

    @Test
    @DisplayName("ADMIN は許可される")
    void admin_isAllowed() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM")).willReturn(true);

        assertThatCode(() -> guard.requireScopeMember(USER_ID, PageViewScopeType.TEAM, SCOPE_ID))
                .doesNotThrowAnyException();
    }
}
