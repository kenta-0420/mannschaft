package com.mannschaft.app.organization.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.organization.dto.OrganizationResponse;
import com.mannschaft.app.organization.dto.UpdateOrganizationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

    @Test
    @DisplayName("updateOrganization: サポーター数の集計が更新コミットより前に行われる")
    void updateOrganization_集計は更新より前() {
        UpdateOrganizationRequest req = new UpdateOrganizationRequest(
                "更新", null, null, null, null, null, null, null, null, 0L);
        given(membershipService.countActiveSupporters(ScopeType.ORGANIZATION, ORG_ID)).willReturn(2L);
        given(organizationService.updateOrganization(ORG_ID, req)).willReturn(ApiResponse.of(orgWithoutSocial()));

        facade.updateOrganization(ORG_ID, req);

        InOrder inOrder = inOrder(membershipService, organizationService);
        inOrder.verify(membershipService).countActiveSupporters(ScopeType.ORGANIZATION, ORG_ID);
        inOrder.verify(organizationService).updateOrganization(ORG_ID, req);
    }

    @Test
    @DisplayName("updateOrganization: 集計が例外を投げたら更新は一切行われない")
    void updateOrganization_集計失敗で更新が走らない() {
        UpdateOrganizationRequest req = new UpdateOrganizationRequest(
                "更新", null, null, null, null, null, null, null, null, 0L);
        given(membershipService.countActiveSupporters(ScopeType.ORGANIZATION, ORG_ID))
                .willThrow(new IllegalStateException("集計失敗"));

        assertThatThrownBy(() -> facade.updateOrganization(ORG_ID, req))
                .isInstanceOf(IllegalStateException.class);

        verify(organizationService, never()).updateOrganization(ORG_ID, req);
    }

    @Test
    @DisplayName("renameSlug: サポーター数の集計がリネームコミットより前に行われる")
    void renameSlug_集計はリネームより前() {
        given(membershipService.countActiveSupporters(ScopeType.ORGANIZATION, ORG_ID)).willReturn(0L);
        given(organizationService.renameSlug(ORG_ID, "new-slug")).willReturn(ApiResponse.of(orgWithoutSocial()));

        facade.renameSlug(ORG_ID, "new-slug");

        InOrder inOrder = inOrder(membershipService, organizationService);
        inOrder.verify(membershipService).countActiveSupporters(ScopeType.ORGANIZATION, ORG_ID);
        inOrder.verify(organizationService).renameSlug(ORG_ID, "new-slug");
    }

    @Test
    @DisplayName("renameSlug: 集計が例外を投げたらリネームは一切行われない")
    void renameSlug_集計失敗でリネームが走らない() {
        given(membershipService.countActiveSupporters(ScopeType.ORGANIZATION, ORG_ID))
                .willThrow(new IllegalStateException("集計失敗"));

        assertThatThrownBy(() -> facade.renameSlug(ORG_ID, "new-slug"))
                .isInstanceOf(IllegalStateException.class);

        verify(organizationService, never()).renameSlug(ORG_ID, "new-slug");
    }

    @Test
    @DisplayName("updateOrganization: 更新が例外を投げたら集計結果は捨てられる（二重集計しない）")
    void updateOrganization_更新失敗で集計結果は捨てられる() {
        UpdateOrganizationRequest req = new UpdateOrganizationRequest(
                "更新", null, null, null, null, null, null, null, null, 0L);
        given(membershipService.countActiveSupporters(ScopeType.ORGANIZATION, ORG_ID)).willReturn(2L);
        given(organizationService.updateOrganization(ORG_ID, req))
                .willThrow(new IllegalStateException("更新失敗"));

        assertThatThrownBy(() -> facade.updateOrganization(ORG_ID, req))
                .isInstanceOf(IllegalStateException.class);
    }
}
