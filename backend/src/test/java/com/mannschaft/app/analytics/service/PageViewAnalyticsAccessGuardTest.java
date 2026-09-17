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
 * 旧仕様（メンバーなら閲覧可）のままだった認可漏れを根治する。ただし
 * <b>ORGANIZATION スコープのみ</b> ADMIN/DEPUTY_ADMIN（および SYSTEM_ADMIN）に限定し、
 * <b>TEAM スコープはチームサイドバー（{@code teamSidebar.item.analytics} は MEMBER 表示）に
 * 合わせて従来どおり MEMBER 閲覧可のまま維持する</b>。いずれのスコープでも非該当は
 * 404（{@code TEAMANALYTICS_001}）で存在を秘匿する（IDOR 隠蔽の設計方針は維持）。
 * 本テストはスコープ差分を番人として固定する（2026-09-17:
 * {@code TeamAnalyticsControllerTest} が accessGuard を丸ごとモックしているため、
 * 最初の是正が TEAM 側の MEMBER 閲覧可を巻き込みかけたのを CI では検知できなかった）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PageViewAnalyticsAccessGuard 認可単体テスト（requireScopeMember・スコープ差分）")
class PageViewAnalyticsAccessGuardTest {

    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private PageViewAnalyticsAccessGuard guard;

    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 100L;

    @Test
    @DisplayName("ORGANIZATIONスコープ: MEMBERは404（TEAMANALYTICS_001）で拒否される")
    void organization_member_isForbidden() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "ORGANIZATION")).willReturn(false);

        assertThatThrownBy(() -> guard.requireScopeMember(USER_ID, PageViewScopeType.ORGANIZATION, SCOPE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TeamOrgAnalyticsErrorCode.TEAMANALYTICS_001);
    }

    @Test
    @DisplayName("ORGANIZATIONスコープ: ADMINは許可される")
    void organization_admin_isAllowed() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "ORGANIZATION")).willReturn(true);

        assertThatCode(() -> guard.requireScopeMember(USER_ID, PageViewScopeType.ORGANIZATION, SCOPE_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TEAMスコープ: MEMBERは許可される（チームサイドバーMEMBER表示を維持）")
    void team_member_isAllowed() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, SCOPE_ID, "TEAM")).willReturn(true);

        assertThatCode(() -> guard.requireScopeMember(USER_ID, PageViewScopeType.TEAM, SCOPE_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TEAMスコープ: 非メンバーは404（TEAMANALYTICS_001）で拒否される")
    void team_nonMember_isForbidden() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, SCOPE_ID, "TEAM")).willReturn(false);

        assertThatThrownBy(() -> guard.requireScopeMember(USER_ID, PageViewScopeType.TEAM, SCOPE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(TeamOrgAnalyticsErrorCode.TEAMANALYTICS_001);
    }
}
