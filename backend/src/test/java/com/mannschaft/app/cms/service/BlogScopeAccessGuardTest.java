package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.VisibilityErrorCode;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link BlogScopeAccessGuard} の単体テスト（CMP-261007-2052）。
 *
 * <p>スコープの解決（チームはチーム・組織は組織、slug・数値の双方）と、不存在・削除済み・PROVISIONED・
 * 閲覧不可を呼び出し側の指定する同一エラーに畳むこと（存在秘匿）を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogScopeAccessGuard 単体テスト")
class BlogScopeAccessGuardTest {

    private static final Long TEAM_ID = 11L;
    private static final Long ORG_ID = 22L;
    private static final Long VIEWER_ID = 99L;

    @Mock
    private TeamService teamService;
    @Mock
    private OrganizationService organizationService;
    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private BlogScopeAccessGuard guard;

    @Test
    @DisplayName("一覧: 数値のチームIDは実在・可視を確かめて返す")
    void 一覧_数値チーム() {
        assertThat(guard.resolveVisibleTeam(TEAM_ID.toString(), VIEWER_ID)).isEqualTo(TEAM_ID);

        verify(teamService).assertActiveTeamExists(TEAM_ID);
        verify(contentVisibilityChecker).assertCanView(ReferenceType.TEAM, TEAM_ID, VIEWER_ID);
        verify(teamService, never()).resolveTeamId(any());
    }

    @Test
    @DisplayName("一覧: チームの slug はチームとして解決する")
    void 一覧_slugチーム() {
        given(teamService.resolveTeamId("fc-tokyo")).willReturn(TEAM_ID);

        assertThat(guard.resolveVisibleTeam("fc-tokyo", VIEWER_ID)).isEqualTo(TEAM_ID);

        verify(contentVisibilityChecker).assertCanView(ReferenceType.TEAM, TEAM_ID, VIEWER_ID);
        verifyNoInteractions(organizationService);
    }

    @Test
    @DisplayName("一覧: 解決できない slug・不存在・閲覧不可はいずれも TEAM_NOT_FOUND")
    void 一覧_チームの存在秘匿() {
        given(teamService.resolveTeamId("no-such")).willThrow(new BusinessException(TeamErrorCode.TEAM_001));
        willThrow(new BusinessException(TeamErrorCode.TEAM_001)).given(teamService).assertActiveTeamExists(404L);
        willThrow(new BusinessException(VisibilityErrorCode.VISIBILITY_001))
                .given(contentVisibilityChecker).assertCanView(ReferenceType.TEAM, TEAM_ID, VIEWER_ID);

        for (String value : new String[] {"no-such", "404", TEAM_ID.toString(), " "}) {
            assertThatThrownBy(() -> guard.resolveVisibleTeam(value, VIEWER_ID))
                    .as(value)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CmsErrorCode.TEAM_NOT_FOUND));
        }
    }

    @Test
    @DisplayName("一覧: 組織の slug は組織として解決し、閲覧不可は ORG_NOT_FOUND")
    void 一覧_組織() {
        given(organizationService.resolveOrgId("org-slug")).willReturn(ORG_ID);
        assertThat(guard.resolveVisibleOrganization("org-slug", VIEWER_ID)).isEqualTo(ORG_ID);
        verify(contentVisibilityChecker).assertCanView(ReferenceType.ORGANIZATION, ORG_ID, VIEWER_ID);
        verify(teamService, never()).resolveTeamId(any());

        willThrow(new BusinessException(OrgErrorCode.ORG_001)).given(organizationService)
                .assertActiveOrganizationExists(505L);
        assertThatThrownBy(() -> guard.resolveVisibleOrganization("505", VIEWER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CmsErrorCode.ORG_NOT_FOUND));
    }

    @Test
    @DisplayName("詳細: チーム優先で解決し、失敗は POST_NOT_FOUND（CMS_001）に畳む")
    void 詳細() {
        given(teamService.resolveTeamId("fc-tokyo")).willReturn(TEAM_ID);
        assertThat(guard.resolveVisibleScopeForDetail("fc-tokyo", "org-slug", VIEWER_ID))
                .isEqualTo(new BlogScopeAccessGuard.ResolvedScope(TEAM_ID, null));
        verify(organizationService, never()).resolveOrgId(any());

        given(organizationService.resolveOrgId("no-such-org")).willThrow(new BusinessException(OrgErrorCode.ORG_001));
        assertThatThrownBy(() -> guard.resolveVisibleScopeForDetail(null, "no-such-org", VIEWER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CmsErrorCode.POST_NOT_FOUND));

        willThrow(new BusinessException(VisibilityErrorCode.VISIBILITY_001))
                .given(contentVisibilityChecker).assertCanView(ReferenceType.ORGANIZATION, ORG_ID, VIEWER_ID);
        assertThatThrownBy(() -> guard.resolveVisibleScopeForDetail("", ORG_ID.toString(), VIEWER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CmsErrorCode.POST_NOT_FOUND));
    }

    @Test
    @DisplayName("詳細: スコープ未指定は個人記事として両方 null（他ドメインを呼ばない）")
    void 詳細_個人記事() {
        assertThat(guard.resolveVisibleScopeForDetail(null, null, VIEWER_ID))
                .isEqualTo(new BlogScopeAccessGuard.ResolvedScope(null, null));
        verifyNoInteractions(teamService, organizationService, contentVisibilityChecker);
    }
}
