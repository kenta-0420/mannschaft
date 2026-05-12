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
 * <p>Aspect から呼び出される薄いサービス。Aspect を経由せずに直接呼べる API として
 * Service 層からも利用可能（ユニットテストの容易化目的でもこの分離を採用している）。</p>
 *
 * <h3>判定ルール</h3>
 * <ol>
 *   <li>{@code scopeType = "TEAM"} の場合:
 *     <ul>
 *       <li>{@code teams.template = "apartment"} でなければ
 *           {@link RepairPlanModuleErrorCode#REPAIR_PLAN_013} を投げる。</li>
 *       <li>当該チームで {@code repair_longterm_plan} モジュールが有効でなければ
 *           {@link RepairPlanModuleErrorCode#REPAIR_PLAN_014} を投げる。</li>
 *     </ul>
 *   </li>
 *   <li>{@code scopeType = "ORGANIZATION"} の場合:
 *     <ul>
 *       <li>組織配下の ACTIVE チームを取得し、いずれかが {@code template = "apartment"} か検査する。
 *           1 件も該当しなければ {@link RepairPlanModuleErrorCode#REPAIR_PLAN_013} を投げる。
 *           （{@code organizations} テーブルには {@code template} カラムが存在しないため、
 *           配下チームの代表テンプレで判定する暫定方式。将来 ORG レベルテンプレ列を追加した場合は
 *           本実装を差し替える。）</li>
 *       <li>apartment 配下チームのうち少なくとも 1 つで
 *           {@code repair_longterm_plan} が有効化されていなければ
 *           {@link RepairPlanModuleErrorCode#REPAIR_PLAN_014} を投げる。</li>
 *     </ul>
 *   </li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepairPlanModuleGuard {

    /** apartment テンプレのスラッグ（{@code team_templates.slug} および {@code teams.template}）。 */
    public static final String APARTMENT_TEMPLATE_SLUG = "apartment";

    /** repair_longterm_plan モジュールのスラッグ。{@code module_definitions.slug} と一致。 */
    public static final String MODULE_SLUG = "repair_longterm_plan";

    private final TeamRepository teamRepository;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final ModuleService moduleService;

    /**
     * 指定スコープが apartment テンプレかつモジュール有効であることを要求する。
     *
     * @param scopeType {@code "TEAM"} / {@code "ORGANIZATION"}
     * @param scopeId   スコープID
     * @throws BusinessException 422 {@code REPAIR_PLAN_013} / {@code REPAIR_PLAN_014}
     */
    public void requireEnabled(String scopeType, Long scopeId) {
        if (scopeType == null || scopeId == null) {
            throw new BusinessException(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
        }
        switch (scopeType) {
            case "TEAM" -> requireEnabledForTeam(scopeId);
            case "ORGANIZATION" -> requireEnabledForOrganization(scopeId);
            default -> {
                log.debug("RepairPlanModuleGuard: 未知の scopeType={} を REPAIR_PLAN_013 で拒否", scopeType);
                throw new BusinessException(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
            }
        }
    }

    /**
     * チームスコープ判定。
     */
    private void requireEnabledForTeam(Long teamId) {
        TeamEntity team = teamRepository.findById(teamId).orElse(null);
        if (team == null || !APARTMENT_TEMPLATE_SLUG.equals(team.getTemplate())) {
            throw new BusinessException(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
        }
        if (!moduleService.isModuleEnabledForTeam(MODULE_SLUG, teamId)) {
            throw new BusinessException(RepairPlanModuleErrorCode.REPAIR_PLAN_014);
        }
    }

    /**
     * 組織スコープ判定。配下 ACTIVE チームのいずれかが apartment かつモジュール有効であることを要求する。
     */
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
