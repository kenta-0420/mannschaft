package com.mannschaft.app.repairplan.module;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.template.service.ModuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * F08.8 Phase 1 — 修繕長期計画ダッシュボードのテンプレ／モジュール判定ロジック本体。
 *
 * scopeType は正規化形式（"TEAM"/"ORGANIZATION"）だけでなく、
 * URL パスセグメント形式（"teams"/"organizations"）も受け付ける。
 * RepairPlanDashboardController のパス変数は AOP 実行前に正規化されないため
 * guard 側で吸収する。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepairPlanModuleGuard {

    public static final String APARTMENT_TEMPLATE_SLUG = "apartment";
    public static final String MODULE_SLUG = "repair_longterm_plan";

    private final TeamRepository teamRepository;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final ModuleService moduleService;

    public void requireEnabled(String scopeType, Long scopeId) {
        if (scopeType == null || scopeId == null) {
            throw new BusinessException(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
        }
        // URL パスセグメント形式（小文字複数形）を正規化する
        String normalized = switch (scopeType.toLowerCase()) {
            case "team", "teams" -> "TEAM";
            case "organization", "organizations" -> "ORGANIZATION";
            default -> scopeType;
        };
        switch (normalized) {
            case "TEAM" -> requireEnabledForTeam(scopeId);
            case "ORGANIZATION" -> requireEnabledForOrganization(scopeId);
            default -> {
                log.debug("RepairPlanModuleGuard: 未知の scopeType={} を REPAIR_PLAN_013 で拒否", scopeType);
                throw new BusinessException(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
            }
        }
    }

    private void requireEnabledForTeam(Long teamId) {
        TeamEntity team = teamRepository.findById(teamId).orElse(null);
        if (team == null || !APARTMENT_TEMPLATE_SLUG.equals(team.getTemplate())) {
            throw new BusinessException(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
        }
        if (!moduleService.isModuleEnabledForTeam(MODULE_SLUG, teamId)) {
            throw new BusinessException(RepairPlanModuleErrorCode.REPAIR_PLAN_014);
        }
    }

    private void requireEnabledForOrganization(Long organizationId) {
        List<TeamOrgMembershipEntity> memberships =
                teamOrgMembershipRepository.findByOrganizationIdAndStatus(
                        organizationId, TeamOrgMembershipEntity.Status.ACTIVE);
        if (memberships.isEmpty()) {
            throw new BusinessException(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
        }

        boolean hasApartment = false;
        boolean hasEnabled = false;
        for (TeamOrgMembershipEntity m : memberships) {
            Long teamId = m.getTeamId();
            TeamEntity team = teamRepository.findById(teamId).orElse(null);
            if (team == null || !APARTMENT_TEMPLATE_SLUG.equals(team.getTemplate())) {
                continue;
            }
            hasApartment = true;
            if (moduleService.isModuleEnabledForTeam(MODULE_SLUG, teamId)) {
                hasEnabled = true;
                break;
            }
        }
        if (!hasApartment) {
            throw new BusinessException(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
        }
        if (!hasEnabled) {
            throw new BusinessException(RepairPlanModuleErrorCode.REPAIR_PLAN_014);
        }
    }
}
