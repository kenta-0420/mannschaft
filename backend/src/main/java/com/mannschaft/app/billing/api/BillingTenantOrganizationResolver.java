package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 契約・特典のテナント {@code organization_id} を、作成時に1度だけ解決する（F01.2.1 §9.2 #17）。
 *
 * <p>USER=null / ORG=scope_id 自身 / TEAM=代表親組織（§9.3: 最初に成立した ACTIVE 加盟。無所属は null）。
 * 解決した値は契約行・特典行へ記録し、<b>以後は再解決しない</b>（加盟先が増減しても契約の組織は変わらない）。
 * SYSTEM_ADMIN の手動付与だけは、TEAM スコープに限り親組織を明示して選べる。</p>
 *
 * <p>billing の 3 箇所（契約作成・シスアド手動付与・ベータ特典手動付与）が個別に持っていた
 * {@code findActiveOrganizationIds(...).get(0)}（並び順不定の先頭）を、ここへ一本化した。</p>
 */
@Component
@RequiredArgsConstructor
public class BillingTenantOrganizationResolver {

    private final TeamOrgMembershipQueryService teamOrgMembershipQueryService;

    /** 作成時の解決（組織の明示なし）。TEAM は代表親組織を採る。 */
    public Long resolveForCreate(EntitlementScopeKind scopeKind, Long scopeId) {
        return switch (scopeKind) {
            case USER -> null;
            case ORG -> scopeId;
            case TEAM -> teamOrgMembershipQueryService.findPrimaryParentOrganizationId(scopeId).orElse(null);
        };
    }

    /**
     * SYSTEM_ADMIN の手動付与での解決。{@code explicitOrganizationId} があれば TEAM スコープに限り採用する。
     *
     * @throws BusinessException TEAM 以外で組織を指定した、または指定組織がそのチームの ACTIVE な親組織でない（400）
     */
    public Long resolveForSystemAdmin(
            EntitlementScopeKind scopeKind, Long scopeId, Long explicitOrganizationId) {
        if (explicitOrganizationId == null) {
            return resolveForCreate(scopeKind, scopeId);
        }
        if (scopeKind != EntitlementScopeKind.TEAM) {
            throw new BusinessException(EntitlementErrorCode.INVALID_SCOPE_KIND);
        }
        List<Long> parents = teamOrgMembershipQueryService.findActiveOrganizationIds(scopeId);
        if (!parents.contains(explicitOrganizationId)) {
            throw new BusinessException(EntitlementErrorCode.INVALID_SCOPE_KIND);
        }
        return explicitOrganizationId;
    }

    /** SYSTEM_ADMIN の画面が選択肢として出す、チームの ACTIVE な親組織 ID 一覧。 */
    public List<Long> candidateOrganizationIds(Long teamId) {
        return teamOrgMembershipQueryService.findActiveOrganizationIds(teamId);
    }

    /** 組織を明示しなかったときに記録される代表親組織（無所属は null）。 */
    public Long representativeOrganizationId(Long teamId) {
        return teamOrgMembershipQueryService.findPrimaryParentOrganizationId(teamId).orElse(null);
    }
}
