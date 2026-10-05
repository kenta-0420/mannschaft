package com.mannschaft.app.organization.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.organization.dto.OrganizationResponse;
import com.mannschaft.app.organization.dto.UpdateOrganizationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link OrganizationDetailFacade} の単体テスト（CMP-261004-1942）。
 *
 * <p>サポーター数は組織のトランザクション（と {@code org-detail} キャッシュ）の外で membership から数えて合成する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationDetailFacade 単体テスト")
class OrganizationDetailFacadeTest {

    private static final Long ORG_ID = 10L;
    private static final String ORG_SLUG = "test-org";

    @Mock private OrganizationService organizationService;
    @Mock private MembershipService membershipService;

    @InjectMocks
    private OrganizationDetailFacade facade;

    private static OrganizationResponse orgWithoutSocial() {
        return OrganizationResponse.builder().id(ORG_SLUG).slug(ORG_SLUG).numericId(ORG_ID).build();
    }

    @Test
    @DisplayName("getOrganization: membership のサポーター数を social.supporterCount に合成する")
    void getOrganization_サポーター数を合成する() {
        given(organizationService.getOrganization(ORG_SLUG)).willReturn(ApiResponse.of(orgWithoutSocial()));
        given(membershipService.countActiveSupporters(ScopeType.ORGANIZATION, ORG_ID)).willReturn(3L);

        OrganizationResponse result = facade.getOrganization(ORG_SLUG).getData();

        assertThat(result.getSocial().supporterCount()).isEqualTo(3L);
        assertThat(result.getSlug()).isEqualTo(ORG_SLUG);
    }

    @Test
    @DisplayName("getOrganization: 0 人は null でなく 0 を返す")
    void getOrganization_0人は0() {
        given(organizationService.getOrganization(ORG_SLUG)).willReturn(ApiResponse.of(orgWithoutSocial()));
        given(membershipService.countActiveSupporters(ScopeType.ORGANIZATION, ORG_ID)).willReturn(0L);

        assertThat(facade.getOrganization(ORG_SLUG).getData().getSocial().supporterCount()).isZero();
    }

    @Test
    @DisplayName("getOrganization: キャッシュから返ったレスポンスを書き換えない（毎回数え直す）")
    void getOrganization_キャッシュ値を書き換えない() {
        ApiResponse<OrganizationResponse> cached = ApiResponse.of(orgWithoutSocial());
        given(organizationService.getOrganization(ORG_SLUG)).willReturn(cached);
        given(membershipService.countActiveSupporters(ScopeType.ORGANIZATION, ORG_ID)).willReturn(1L, 0L);

        assertThat(facade.getOrganization(ORG_SLUG).getData().getSocial().supporterCount()).isEqualTo(1L);
        assertThat(facade.getOrganization(ORG_SLUG).getData().getSocial().supporterCount()).isZero();
        assertThat(cached.getData().getSocial()).isNull();
    }

    @Test
    @DisplayName("updateOrganization: 更新後の応答にもサポーター数を合成する")
    void updateOrganization_サポーター数を合成する() {
        UpdateOrganizationRequest req = new UpdateOrganizationRequest(
                "更新", null, null, null, null, null, null, null, null, 0L);
        given(organizationService.updateOrganization(ORG_ID, req)).willReturn(ApiResponse.of(orgWithoutSocial()));
        given(membershipService.countActiveSupporters(ScopeType.ORGANIZATION, ORG_ID)).willReturn(2L);

        assertThat(facade.updateOrganization(ORG_ID, req).getData().getSocial().supporterCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("renameSlug: リネーム後の応答にもサポーター数を合成する")
    void renameSlug_サポーター数を合成する() {
        given(organizationService.renameSlug(ORG_ID, "new-slug")).willReturn(ApiResponse.of(orgWithoutSocial()));
        given(membershipService.countActiveSupporters(ScopeType.ORGANIZATION, ORG_ID)).willReturn(0L);

        assertThat(facade.renameSlug(ORG_ID, "new-slug").getData().getSocial().supporterCount()).isZero();
    }
}
