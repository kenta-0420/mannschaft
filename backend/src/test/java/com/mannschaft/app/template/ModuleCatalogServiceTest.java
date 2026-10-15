package com.mannschaft.app.template;

import com.mannschaft.app.payment.service.TeamPlanService;
import com.mannschaft.app.template.dto.OrgModuleCatalogResponse;
import com.mannschaft.app.template.dto.TeamModuleCatalogResponse;
import com.mannschaft.app.template.entity.ModuleDefinitionEntity;
import com.mannschaft.app.template.entity.ModuleLevelAvailabilityEntity;
import com.mannschaft.app.template.entity.OrganizationEnabledModuleEntity;
import com.mannschaft.app.template.entity.TeamEnabledModuleEntity;
import com.mannschaft.app.template.repository.ModuleDefinitionRepository;
import com.mannschaft.app.template.repository.ModuleLevelAvailabilityRepository;
import com.mannschaft.app.template.repository.ModuleRecommendationRepository;
import com.mannschaft.app.template.repository.OrganizationEnabledModuleRepository;
import com.mannschaft.app.template.repository.TeamEnabledModuleRepository;
import com.mannschaft.app.template.repository.TeamTemplateRepository;
import com.mannschaft.app.template.repository.TemplateModuleRepository;
import com.mannschaft.app.template.service.ModuleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * {@link ModuleService} のカタログ＋有効状態取得 API（機能設定タブ向け）の単体テスト。
 *
 * <p>受け入れ条件 AC-1〜AC-7 を検証する（認可・404 はコントローラーテストで検証）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModuleService カタログ＋有効状態取得 単体テスト")
class ModuleCatalogServiceTest {

    @Mock private ModuleDefinitionRepository moduleDefinitionRepository;
    @Mock private ModuleLevelAvailabilityRepository moduleLevelAvailabilityRepository;
    @Mock private ModuleRecommendationRepository moduleRecommendationRepository;
    @Mock private TeamEnabledModuleRepository teamEnabledModuleRepository;
    @Mock private OrganizationEnabledModuleRepository organizationEnabledModuleRepository;
    @Mock private TemplateModuleRepository templateModuleRepository;
    @Mock private TeamTemplateRepository teamTemplateRepository;
    @Mock private TeamPlanService teamPlanService;

    @InjectMocks
    private ModuleService moduleService;

    private static final Long TEAM_ID = 1L;
    private static final Long ORG_ID = 200L;

    // ========================================
    // テスト用ヘルパー
    // ========================================

    /** ID を設定した OPTIONAL モジュールを生成する（BaseEntity.id は reflection でセット）。 */
    private ModuleDefinitionEntity optionalModule(Long id, String name, String slug,
                                                  int moduleNumber, boolean requiresPaidPlan,
                                                  Integer trialDays) {
        ModuleDefinitionEntity module = ModuleDefinitionEntity.builder()
                .name(name)
                .slug(slug)
                .description(name + "の説明")
                .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                .moduleNumber(moduleNumber)
                .requiresPaidPlan(requiresPaidPlan)
                .trialDays(trialDays)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(module, "id", id);
        return module;
    }

    private ModuleDefinitionEntity inactiveOptionalModule(Long id) {
        ModuleDefinitionEntity module = ModuleDefinitionEntity.builder()
                .name("無効モジュール")
                .slug("inactive")
                .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                .moduleNumber(99)
                .requiresPaidPlan(false)
                .isActive(false)
                .build();
        ReflectionTestUtils.setField(module, "id", id);
        return module;
    }

    private TeamEnabledModuleEntity teamRow(Long moduleId, boolean enabled, LocalDateTime trialExpiresAt) {
        return TeamEnabledModuleEntity.builder()
                .teamId(TEAM_ID)
                .moduleId(moduleId)
                .isEnabled(enabled)
                .enabledAt(enabled ? LocalDateTime.now() : null)
                .trialExpiresAt(trialExpiresAt)
                .trialUsed(false)
                .build();
    }

    private OrganizationEnabledModuleEntity orgRow(Long moduleId, boolean enabled) {
        return OrganizationEnabledModuleEntity.builder()
                .organizationId(ORG_ID)
                .moduleId(moduleId)
                .isEnabled(enabled)
                .enabledAt(enabled ? LocalDateTime.now() : null)
                .build();
    }

    // ========================================
    // チームカタログ
    // ========================================

    @Nested
    @DisplayName("getTeamModuleCatalog")
    class GetTeamModuleCatalog {

        @Test
        @DisplayName("AC-1: OPTIONAL かつ active のみ返す（DEFAULT/非activeを含まない）")
        void AC1_OPTIONALかつactiveのみ() {
            ModuleDefinitionEntity m1 = optionalModule(10L, "予約管理", "reservation", 1, false, 30);
            ModuleDefinitionEntity inactive = inactiveOptionalModule(11L);
            // findByModuleType(OPTIONAL) は OPTIONAL のみ返す（DEFAULT は含まれない）
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of(m1, inactive));
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(List.of());
            lenient().when(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    eq(10L), eq(ModuleLevelAvailabilityEntity.Level.TEAM))).thenReturn(Optional.empty());
            given(teamPlanService.hasPaidPlan(TEAM_ID)).willReturn(false);

            TeamModuleCatalogResponse result = moduleService.getTeamModuleCatalog(TEAM_ID);

            assertThat(result.getModules()).hasSize(1);
            assertThat(result.getModules().get(0).getSlug()).isEqualTo("reservation");
        }

        @Test
        @DisplayName("AC-2: 有効化済みは isEnabled=true、未登録は false")
        void AC2_有効状態反映() {
            ModuleDefinitionEntity enabled = optionalModule(10L, "予約管理", "reservation", 1, false, null);
            ModuleDefinitionEntity notEnabled = optionalModule(20L, "在庫管理", "inventory", 2, false, null);
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of(enabled, notEnabled));
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID))
                    .willReturn(List.of(teamRow(10L, true, null)));
            given(teamPlanService.hasPaidPlan(TEAM_ID)).willReturn(false);

            TeamModuleCatalogResponse result = moduleService.getTeamModuleCatalog(TEAM_ID);

            assertThat(result.getModules()).hasSize(2);
            assertThat(result.getModules().get(0).getModuleId()).isEqualTo(10L);
            assertThat(result.getModules().get(0).getIsEnabled()).isTrue();
            assertThat(result.getModules().get(1).getModuleId()).isEqualTo(20L);
            assertThat(result.getModules().get(1).getIsEnabled()).isFalse();
        }

        @Test
        @DisplayName("AC-2b: is_enabled=false の行は isEnabled=false")
        void AC2b_無効行はfalse() {
            ModuleDefinitionEntity module = optionalModule(10L, "予約管理", "reservation", 1, false, null);
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of(module));
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID))
                    .willReturn(List.of(teamRow(10L, false, null)));
            given(teamPlanService.hasPaidPlan(TEAM_ID)).willReturn(false);

            TeamModuleCatalogResponse result = moduleService.getTeamModuleCatalog(TEAM_ID);

            assertThat(result.getModules().get(0).getIsEnabled()).isFalse();
        }

        @Test
        @DisplayName("AC-3: planLimit=10 / enabledCount 正しい / hasPaidPlan を含む")
        void AC3_planLimitとenabledCountとhasPaidPlan() {
            ModuleDefinitionEntity m1 = optionalModule(10L, "予約管理", "reservation", 1, false, null);
            ModuleDefinitionEntity m2 = optionalModule(20L, "在庫管理", "inventory", 2, false, null);
            ModuleDefinitionEntity m3 = optionalModule(30L, "分析", "analytics", 3, false, null);
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of(m1, m2, m3));
            // 2件有効、1件は is_enabled=false（カウントされない）
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID))
                    .willReturn(List.of(teamRow(10L, true, null), teamRow(20L, true, null), teamRow(30L, false, null)));
            given(teamPlanService.hasPaidPlan(TEAM_ID)).willReturn(true);

            TeamModuleCatalogResponse result = moduleService.getTeamModuleCatalog(TEAM_ID);

            assertThat(result.getPlanLimit()).isEqualTo(10);
            assertThat(result.getEnabledCount()).isEqualTo(2);
            assertThat(result.getHasPaidPlan()).isTrue();
        }

        @Test
        @DisplayName("AC-4: level不可(is_available=0)は levelAvailable=false、レコード無は true")
        void AC4_levelAvailable判定() {
            ModuleDefinitionEntity available = optionalModule(10L, "予約管理", "reservation", 1, false, null);
            ModuleDefinitionEntity unavailable = optionalModule(20L, "在庫管理", "inventory", 2, false, null);
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of(available, unavailable));
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(List.of());
            // 10L はレコード無 → true
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    10L, ModuleLevelAvailabilityEntity.Level.TEAM)).willReturn(Optional.empty());
            // 20L は is_available=false → false
            ModuleLevelAvailabilityEntity la = ModuleLevelAvailabilityEntity.builder()
                    .moduleId(20L)
                    .level(ModuleLevelAvailabilityEntity.Level.TEAM)
                    .isAvailable(false)
                    .build();
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    20L, ModuleLevelAvailabilityEntity.Level.TEAM)).willReturn(Optional.of(la));
            given(teamPlanService.hasPaidPlan(TEAM_ID)).willReturn(false);

            TeamModuleCatalogResponse result = moduleService.getTeamModuleCatalog(TEAM_ID);

            assertThat(result.getModules().get(0).getLevelAvailable()).isTrue();
            assertThat(result.getModules().get(1).getLevelAvailable()).isFalse();
        }

        @Test
        @DisplayName("AC-5: team は trialExpiresAt を含む（有効化行の値を返す）")
        void AC5_trialExpiresAtを含む() {
            ModuleDefinitionEntity module = optionalModule(10L, "予約管理", "reservation", 1, false, 14);
            LocalDateTime trial = LocalDateTime.now().plusDays(14);
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of(module));
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID))
                    .willReturn(List.of(teamRow(10L, true, trial)));
            given(teamPlanService.hasPaidPlan(TEAM_ID)).willReturn(false);

            TeamModuleCatalogResponse result = moduleService.getTeamModuleCatalog(TEAM_ID);

            assertThat(result.getModules().get(0).getTrialExpiresAt()).isEqualTo(trial);
        }

        @Test
        @DisplayName("AC-6: modules は moduleNumber 昇順")
        void AC6_moduleNumber昇順() {
            ModuleDefinitionEntity m3 = optionalModule(30L, "分析", "analytics", 3, false, null);
            ModuleDefinitionEntity m1 = optionalModule(10L, "予約管理", "reservation", 1, false, null);
            ModuleDefinitionEntity m2 = optionalModule(20L, "在庫管理", "inventory", 2, false, null);
            // リポジトリは順不同で返す
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of(m3, m1, m2));
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(List.of());
            given(teamPlanService.hasPaidPlan(TEAM_ID)).willReturn(false);

            TeamModuleCatalogResponse result = moduleService.getTeamModuleCatalog(TEAM_ID);

            assertThat(result.getModules()).extracting(item -> item.getModuleNumber())
                    .containsExactly(1, 2, 3);
        }
    }

    // ========================================
    // 組織カタログ
    // ========================================

    @Nested
    @DisplayName("getOrganizationModuleCatalog")
    class GetOrganizationModuleCatalog {

        @Test
        @DisplayName("AC-7: org版は level=ORGANIZATION で判定し、有効状態を反映する")
        void AC7_organizationレベルで判定() {
            ModuleDefinitionEntity enabled = optionalModule(10L, "予約管理", "reservation", 1, false, null);
            ModuleDefinitionEntity unavailable = optionalModule(20L, "在庫管理", "inventory", 2, false, null);
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of(enabled, unavailable));
            given(organizationEnabledModuleRepository.findByOrganizationId(ORG_ID))
                    .willReturn(List.of(orgRow(10L, true)));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    10L, ModuleLevelAvailabilityEntity.Level.ORGANIZATION)).willReturn(Optional.empty());
            ModuleLevelAvailabilityEntity la = ModuleLevelAvailabilityEntity.builder()
                    .moduleId(20L)
                    .level(ModuleLevelAvailabilityEntity.Level.ORGANIZATION)
                    .isAvailable(false)
                    .build();
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    20L, ModuleLevelAvailabilityEntity.Level.ORGANIZATION)).willReturn(Optional.of(la));

            OrgModuleCatalogResponse result = moduleService.getOrganizationModuleCatalog(ORG_ID);

            assertThat(result.getModules()).hasSize(2);
            assertThat(result.getModules().get(0).getModuleId()).isEqualTo(10L);
            assertThat(result.getModules().get(0).getIsEnabled()).isTrue();
            assertThat(result.getModules().get(0).getLevelAvailable()).isTrue();
            assertThat(result.getModules().get(1).getIsEnabled()).isFalse();
            assertThat(result.getModules().get(1).getLevelAvailable()).isFalse();
        }

        @Test
        @DisplayName("AC-3/AC-7: org の enabledCount / planLimit / hasPaidPlan=false 固定")
        void org_集計とhasPaidPlanFalse() {
            ModuleDefinitionEntity m1 = optionalModule(10L, "予約管理", "reservation", 1, false, null);
            ModuleDefinitionEntity m2 = optionalModule(20L, "在庫管理", "inventory", 2, false, null);
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of(m1, m2));
            given(organizationEnabledModuleRepository.findByOrganizationId(ORG_ID))
                    .willReturn(List.of(orgRow(10L, true), orgRow(20L, false)));

            OrgModuleCatalogResponse result = moduleService.getOrganizationModuleCatalog(ORG_ID);

            assertThat(result.getPlanLimit()).isEqualTo(10);
            assertThat(result.getEnabledCount()).isEqualTo(1);
            // 組織側に有料プラン判定が無いため false 固定
            assertThat(result.getHasPaidPlan()).isFalse();
        }
    }
}
