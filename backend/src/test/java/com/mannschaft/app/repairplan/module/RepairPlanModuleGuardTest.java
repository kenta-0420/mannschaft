package com.mannschaft.app.repairplan.module;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.template.service.ModuleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link RepairPlanModuleGuard} の単体テスト。
 *
 * <p>apartment テンプレ判定と {@code repair_longterm_plan} モジュール有効化判定が
 * scopeType = TEAM / ORGANIZATION で期待通り動作することを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RepairPlanModuleGuard 単体テスト")
class RepairPlanModuleGuardTest {

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @Mock
    private ModuleService moduleService;

    @InjectMocks
    private RepairPlanModuleGuard guard;

    private static final Long TEAM_ID = 1001L;
    private static final Long ORG_ID = 2001L;

    private TeamEntity teamWithTemplate(String template) {
        return TeamEntity.builder()
                .name("テストチーム")
                .template(template)
                .visibility(TeamEntity.Visibility.GUESTS_AND_ABOVE)
                .supporterEnabled(false)
                .build();
    }

    private TeamOrgMembershipEntity membership(Long teamId) {
        return TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(ORG_ID)
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("scopeType = TEAM")
    class TeamScope {

        @Test
        @DisplayName("apartment テンプレ かつ モジュール有効 → 通過")
        void apartment_and_enabled_passes() {
            given(teamRepository.findById(TEAM_ID))
                    .willReturn(Optional.of(teamWithTemplate("apartment")));
            given(moduleService.isModuleEnabledForTeam(
                    RepairPlanModuleGuard.MODULE_SLUG, TEAM_ID))
                    .willReturn(true);

            assertThatCode(() -> guard.requireEnabled("TEAM", TEAM_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("テンプレが apartment 以外 → REPAIR_PLAN_013")
        void non_apartment_throws_013() {
            given(teamRepository.findById(TEAM_ID))
                    .willReturn(Optional.of(teamWithTemplate("sports")));

            assertThatThrownBy(() -> guard.requireEnabled("TEAM", TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
        }

        @Test
        @DisplayName("チームが存在しない → REPAIR_PLAN_013")
        void missing_team_throws_013() {
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.requireEnabled("TEAM", TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
        }

        @Test
        @DisplayName("apartment だがモジュール無効 → REPAIR_PLAN_014")
        void disabled_module_throws_014() {
            given(teamRepository.findById(TEAM_ID))
                    .willReturn(Optional.of(teamWithTemplate("apartment")));
            given(moduleService.isModuleEnabledForTeam(
                    RepairPlanModuleGuard.MODULE_SLUG, TEAM_ID))
                    .willReturn(false);

            assertThatThrownBy(() -> guard.requireEnabled("TEAM", TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(RepairPlanModuleErrorCode.REPAIR_PLAN_014);
        }
    }

    @Nested
    @DisplayName("scopeType = ORGANIZATION")
    class OrgScope {

        @Test
        @DisplayName("配下に apartment チームが存在し有効化済み → 通過")
        void apartment_team_in_org_passes() {
            given(teamOrgMembershipRepository.findByOrganizationIdAndStatus(
                    ORG_ID, TeamOrgMembershipEntity.Status.ACTIVE))
                    .willReturn(List.of(membership(TEAM_ID)));
            given(teamRepository.findById(TEAM_ID))
                    .willReturn(Optional.of(teamWithTemplate("apartment")));
            given(moduleService.isModuleEnabledForTeam(
                    RepairPlanModuleGuard.MODULE_SLUG, TEAM_ID))
                    .willReturn(true);

            assertThatCode(() -> guard.requireEnabled("ORGANIZATION", ORG_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("配下チームが空 → REPAIR_PLAN_013")
        void empty_org_throws_013() {
            given(teamOrgMembershipRepository.findByOrganizationIdAndStatus(
                    ORG_ID, TeamOrgMembershipEntity.Status.ACTIVE))
                    .willReturn(List.of());

            assertThatThrownBy(() -> guard.requireEnabled("ORGANIZATION", ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
        }

        @Test
        @DisplayName("配下チームが全て非 apartment → REPAIR_PLAN_013")
        void non_apartment_only_throws_013() {
            given(teamOrgMembershipRepository.findByOrganizationIdAndStatus(
                    ORG_ID, TeamOrgMembershipEntity.Status.ACTIVE))
                    .willReturn(List.of(membership(TEAM_ID)));
            given(teamRepository.findById(TEAM_ID))
                    .willReturn(Optional.of(teamWithTemplate("school")));

            assertThatThrownBy(() -> guard.requireEnabled("ORGANIZATION", ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
        }

        @Test
        @DisplayName("apartment は存在するが誰もモジュール有効化していない → REPAIR_PLAN_014")
        void apartment_but_disabled_throws_014() {
            Long teamA = 1L;
            Long teamB = 2L;
            given(teamOrgMembershipRepository.findByOrganizationIdAndStatus(
                    ORG_ID, TeamOrgMembershipEntity.Status.ACTIVE))
                    .willReturn(List.of(membership(teamA), membership(teamB)));
            given(teamRepository.findById(teamA))
                    .willReturn(Optional.of(teamWithTemplate("apartment")));
            given(teamRepository.findById(teamB))
                    .willReturn(Optional.of(teamWithTemplate("apartment")));
            given(moduleService.isModuleEnabledForTeam(
                    RepairPlanModuleGuard.MODULE_SLUG, teamA))
                    .willReturn(false);
            given(moduleService.isModuleEnabledForTeam(
                    RepairPlanModuleGuard.MODULE_SLUG, teamB))
                    .willReturn(false);

            assertThatThrownBy(() -> guard.requireEnabled("ORGANIZATION", ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(RepairPlanModuleErrorCode.REPAIR_PLAN_014);
        }
    }

    @Nested
    @DisplayName("不正な引数")
    class InvalidArgs {

        @Test
        @DisplayName("scopeType が null → REPAIR_PLAN_013")
        void null_scope_type_throws_013() {
            assertThatThrownBy(() -> guard.requireEnabled(null, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
        }

        @Test
        @DisplayName("scopeId が null → REPAIR_PLAN_013")
        void null_scope_id_throws_013() {
            assertThatThrownBy(() -> guard.requireEnabled("TEAM", null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
        }

        @Test
        @DisplayName("未知の scopeType → REPAIR_PLAN_013")
        void unknown_scope_type_throws_013() {
            assertThatThrownBy(() -> guard.requireEnabled("PERSONAL", 1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
        }
    }
}
