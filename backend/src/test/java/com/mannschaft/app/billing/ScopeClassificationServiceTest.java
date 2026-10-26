package com.mannschaft.app.billing;

import com.mannschaft.app.organization.service.OrganizationQueryService;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ScopeClassificationService} 単体テスト（試練先行）。
 *
 * <p>対象 AC: AC-13（無所属チーム＝非営利扱い・R-2）。加えて USER 常に false・ORG/TEAM の org_type 導出を検証。
 * 他ドメインは公開クエリサービス（{@link OrganizationQueryService} / {@link TeamOrgMembershipQueryService}）
 * 経由で参照するため、それらをモックする（Entity/Repository は直接触らない＝ドメイン境界番人 D-1 準拠）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScopeClassificationService 単体テスト（営利/非営利判定・R-2）")
class ScopeClassificationServiceTest {

    @Mock
    private OrganizationQueryService organizationQueryService;
    @Mock
    private TeamOrgMembershipQueryService teamOrgMembershipQueryService;

    @InjectMocks
    private ScopeClassificationService service;

    @Test
    @DisplayName("USER は常に非営利判定 false（クエリサービスを呼ばない）")
    void userAlwaysFalse() {
        assertThat(service.isNonProfitScope(EntitlementScopeKind.USER, 1L)).isFalse();
        verifyNoInteractions(organizationQueryService, teamOrgMembershipQueryService);
    }

    @Test
    @DisplayName("ORG: 組織が非営利（org_type != COMPANY）なら true")
    void orgNonProfitIsTrue() {
        given(organizationQueryService.isNonProfit(5L)).willReturn(true);
        assertThat(service.isNonProfitScope(EntitlementScopeKind.ORG, 5L)).isTrue();
    }

    @Test
    @DisplayName("ORG: 組織が営利（COMPANY）なら false")
    void orgForProfitIsFalse() {
        given(organizationQueryService.isNonProfit(5L)).willReturn(false);
        assertThat(service.isNonProfitScope(EntitlementScopeKind.ORG, 5L)).isFalse();
    }

    @Test
    @DisplayName("AC-13: 無所属チーム（ACTIVE 所属組織なし）は非営利扱い（true）")
    void ac13_teamWithoutOrgIsNonProfit() {
        given(teamOrgMembershipQueryService.findActiveOrganizationIds(10L)).willReturn(List.of());
        assertThat(service.isNonProfitScope(EntitlementScopeKind.TEAM, 10L)).isTrue();
    }

    @Test
    @DisplayName("TEAM: ACTIVE 所属組織に営利（COMPANY）が 1 つでもあれば営利扱い（false）")
    void teamWithCompanyOrgIsForProfit() {
        given(teamOrgMembershipQueryService.findActiveOrganizationIds(10L)).willReturn(List.of(5L, 6L));
        given(organizationQueryService.isNonProfit(5L)).willReturn(true);
        given(organizationQueryService.isNonProfit(6L)).willReturn(false); // COMPANY

        assertThat(service.isNonProfitScope(EntitlementScopeKind.TEAM, 10L)).isFalse();
    }

    @Test
    @DisplayName("TEAM: ACTIVE 所属組織が全て非営利なら非営利扱い（true）")
    void teamWithAllNonProfitOrgsIsNonProfit() {
        given(teamOrgMembershipQueryService.findActiveOrganizationIds(10L)).willReturn(List.of(5L, 7L));
        given(organizationQueryService.isNonProfit(5L)).willReturn(true);
        given(organizationQueryService.isNonProfit(7L)).willReturn(true);

        assertThat(service.isNonProfitScope(EntitlementScopeKind.TEAM, 10L)).isTrue();
    }
}
