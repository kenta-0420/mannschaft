package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.api.dto.TeamParentOrganizationsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * シスアド手動付与の画面が選ぶ、チームの親組織の候補と代表親組織（F01.2.1 §9.2 #17）。
 *
 * <p>読み取りだけで契約 tx を持たない。{@code @Transactional} を付けないのは、team・organization
 * ドメインの Repository へ契約 tx の入口から到達させないため（CLAUDE.md DB 設計の原則 #5・D-3T 番人）。
 * 各 Query Service が自ドメイン内で readOnly tx を張る。</p>
 */
@Service
@RequiredArgsConstructor
public class SystemAdminTeamParentOrganizationQueryService {

    private final BillingTenantOrganizationResolver tenantOrganizationResolver;

    public TeamParentOrganizationsResponse teamParentOrganizations(Long teamId) {
        return TeamParentOrganizationsResponse.builder()
                .organizationIds(tenantOrganizationResolver.candidateOrganizationIds(teamId))
                .organizations(tenantOrganizationResolver.candidateOrganizations(teamId).stream()
                        .map(o -> TeamParentOrganizationsResponse.Organization.builder()
                                .organizationId(o.id()).name(o.name()).slug(o.slug()).build())
                        .toList())
                .representativeOrganizationId(tenantOrganizationResolver.representativeOrganizationId(teamId))
                .build();
    }
}
