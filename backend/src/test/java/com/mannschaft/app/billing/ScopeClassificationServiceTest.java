package com.mannschaft.app.billing;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ScopeClassificationService} 単体テスト（試練先行）。
 *
 * <p>対象 AC: AC-13（無所属チーム＝非営利扱い・R-2）。加えて USER 常に false・ORG/TEAM の org_type 導出を検証。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScopeClassificationService 単体テスト（営利/非営利判定・R-2）")
class ScopeClassificationServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @InjectMocks
    private ScopeClassificationService service;

    private OrganizationEntity org(OrganizationEntity.OrgType type) {
        return OrganizationEntity.builder().orgType(type).build();
    }

    private TeamOrgMembershipEntity membership(Long orgId) {
        return TeamOrgMembershipEntity.builder()
                .teamId(10L)
                .organizationId(orgId)
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
                .invitedAt(java.time.LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("USER は常に非営利判定 false（リポジトリを呼ばない）")
    void userAlwaysFalse() {
        assertThat(service.isNonProfitScope(EntitlementScopeKind.USER, 1L)).isFalse();
        verifyNoInteractions(organizationRepository, teamOrgMembershipRepository);
    }

    @Test
    @DisplayName("ORG: org_type=NPO は非営利扱い（true）")
    void orgNpoIsNonProfit() {
        given(organizationRepository.findById(5L)).willReturn(Optional.of(org(OrganizationEntity.OrgType.NPO)));
        assertThat(service.isNonProfitScope(EntitlementScopeKind.ORG, 5L)).isTrue();
    }

    @Test
    @DisplayName("ORG: org_type=COMPANY は営利扱い（false）")
    void orgCompanyIsForProfit() {
        given(organizationRepository.findById(5L)).willReturn(Optional.of(org(OrganizationEntity.OrgType.COMPANY)));
        assertThat(service.isNonProfitScope(EntitlementScopeKind.ORG, 5L)).isFalse();
    }

    @Test
    @DisplayName("AC-13: 無所属チーム（ACTIVE 所属組織なし）は非営利扱い（true）")
    void ac13_teamWithoutOrgIsNonProfit() {
        given(teamOrgMembershipRepository.findByTeamIdAndStatus(10L, TeamOrgMembershipEntity.Status.ACTIVE))
                .willReturn(List.of());
        assertThat(service.isNonProfitScope(EntitlementScopeKind.TEAM, 10L)).isTrue();
    }

    @Test
    @DisplayName("TEAM: ACTIVE 所属組織に COMPANY が 1 つでもあれば営利扱い（false）")
    void teamWithCompanyOrgIsForProfit() {
        given(teamOrgMembershipRepository.findByTeamIdAndStatus(10L, TeamOrgMembershipEntity.Status.ACTIVE))
                .willReturn(List.of(membership(5L), membership(6L)));
        given(organizationRepository.findById(5L)).willReturn(Optional.of(org(OrganizationEntity.OrgType.NPO)));
        given(organizationRepository.findById(6L)).willReturn(Optional.of(org(OrganizationEntity.OrgType.COMPANY)));

        assertThat(service.isNonProfitScope(EntitlementScopeKind.TEAM, 10L)).isFalse();
    }

    @Test
    @DisplayName("TEAM: ACTIVE 所属組織が全て非営利なら非営利扱い（true）")
    void teamWithAllNonProfitOrgsIsNonProfit() {
        given(teamOrgMembershipRepository.findByTeamIdAndStatus(10L, TeamOrgMembershipEntity.Status.ACTIVE))
                .willReturn(List.of(membership(5L), membership(7L)));
        given(organizationRepository.findById(5L)).willReturn(Optional.of(org(OrganizationEntity.OrgType.NPO)));
        given(organizationRepository.findById(7L)).willReturn(Optional.of(org(OrganizationEntity.OrgType.ASSOCIATION)));

        assertThat(service.isNonProfitScope(EntitlementScopeKind.TEAM, 10L)).isTrue();
    }
}
