package com.mannschaft.app.billing;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F20.1: スコープの営利/非営利判定（{@code free_for_nonprofit} 無料枠の分岐・設計書 README §3.3 / 02 §1.1）。
 *
 * <p>初期スコープでは {@code feature_catalog.free_for_nonprofit} が全 FALSE のため
 * {@code isEntitled} からは実質到達しないが、判定ロジック（USER 既定 false・無所属チーム=非営利）を
 * 正しく実装して将来の詰まりを予防する（設計書 02 §1.1 コメント E）。営利自動切替（org_type 自動変異）は
 * Phase 2 保留であり、本サービスは org_type を<b>読むだけ</b>で書き換えない。</p>
 *
 * <p><b>判定規約</b>（マスター御裁可済 R-2）:</p>
 * <ul>
 *   <li>{@code USER} → 常に {@code false}（個人に営利/非営利の区分は無い）。</li>
 *   <li>{@code ORG} → {@code organizations.org_type} が {@code COMPANY} 以外なら非営利扱い。</li>
 *   <li>{@code TEAM} → ACTIVE な所属組織のうち 1 つでも {@code COMPANY} なら営利扱い、
 *       全所属が非営利 or <b>無所属</b>なら非営利扱い（AC-13）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScopeClassificationService {

    private final OrganizationRepository organizationRepository;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;

    /**
     * 対象スコープが非営利扱いか判定する。
     *
     * @param scopeKind USER / TEAM / ORG
     * @param scopeId   users.id / teams.id / organizations.id
     * @return 非営利扱いなら true
     */
    public boolean isNonProfitScope(EntitlementScopeKind scopeKind, Long scopeId) {
        if (scopeKind == null) {
            return false;
        }
        switch (scopeKind) {
            case USER:
                // 個人スコープに営利/非営利の区分は無い（将来も個人無料枠は free_for_nonprofit で表現しない）。
                return false;
            case ORG:
                return isNonProfitOrg(scopeId);
            case TEAM:
                return isNonProfitTeam(scopeId);
            default:
                return false;
        }
    }

    private boolean isNonProfitOrg(Long orgId) {
        OrganizationEntity org = organizationRepository.findById(orgId).orElse(null);
        if (org == null || org.getOrgType() == null) {
            // 不明組織は fail-safe で営利扱い（無料枠を与えない）。
            return false;
        }
        return org.getOrgType() != OrganizationEntity.OrgType.COMPANY;
    }

    private boolean isNonProfitTeam(Long teamId) {
        List<TeamOrgMembershipEntity> activeMemberships =
                teamOrgMembershipRepository.findByTeamIdAndStatus(teamId, TeamOrgMembershipEntity.Status.ACTIVE);
        if (activeMemberships == null || activeMemberships.isEmpty()) {
            // 無所属チームは非営利扱い（列追加なし・都度導出・R-2 御裁可済・AC-13）。
            return true;
        }
        // ACTIVE な所属組織のうち 1 つでも COMPANY なら営利扱い（全非営利なら非営利扱い）。
        for (TeamOrgMembershipEntity m : activeMemberships) {
            OrganizationEntity org = organizationRepository.findById(m.getOrganizationId()).orElse(null);
            if (org != null && org.getOrgType() == OrganizationEntity.OrgType.COMPANY) {
                return false;
            }
        }
        return true;
    }
}
