package com.mannschaft.app.template;

import com.mannschaft.app.billing.EntitlementQueryService;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.service.TeamPlanService;
import com.mannschaft.app.template.dto.ModuleResponse;
import com.mannschaft.app.template.dto.OrgModuleCatalogResponse;
import com.mannschaft.app.template.dto.TeamModuleCatalogResponse;
import com.mannschaft.app.template.dto.TeamModuleResponse;
import com.mannschaft.app.template.dto.ToggleModuleRequest;
import com.mannschaft.app.template.entity.ModuleDefinitionEntity;
import com.mannschaft.app.template.entity.ModuleLevelAvailabilityEntity;
import com.mannschaft.app.template.entity.ModuleRecommendationEntity;
import com.mannschaft.app.template.entity.OrganizationEnabledModuleEntity;
import com.mannschaft.app.template.entity.TeamEnabledModuleEntity;
import com.mannschaft.app.template.entity.TeamTemplateEntity;
import com.mannschaft.app.template.entity.TemplateModuleEntity;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ModuleService} の単体テスト。
 * モジュールカタログ参照・チームモジュール管理・テンプレート適用・有効判定を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModuleService 単体テスト")
class ModuleServiceTest {

    @Mock
    private ModuleDefinitionRepository moduleDefinitionRepository;

    @Mock
    private ModuleLevelAvailabilityRepository moduleLevelAvailabilityRepository;

    @Mock
    private ModuleRecommendationRepository moduleRecommendationRepository;

    @Mock
    private TeamEnabledModuleRepository teamEnabledModuleRepository;

    @Mock
    private OrganizationEnabledModuleRepository organizationEnabledModuleRepository;

    @Mock
    private TemplateModuleRepository templateModuleRepository;

    @Mock
    private TeamTemplateRepository teamTemplateRepository;

    @Mock
    private TeamPlanService teamPlanService;

    @Mock
    private EntitlementQueryService entitlementQueryService;

    @InjectMocks
    private ModuleService moduleService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long MODULE_ID = 10L;
    private static final Long TEAM_ID = 1L;
    private static final Long TEMPLATE_ID = 5L;
    private static final Long USER_ID = 100L;

    private ModuleDefinitionEntity createOptionalModule() {
        return ModuleDefinitionEntity.builder()
                .name("予約管理")
                .slug("reservation")
                .description("予約管理モジュール")
                .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                .moduleNumber(1)
                .requiresPaidPlan(false)
                .trialDays(30)
                .isActive(true)
                .build();
    }

    private ModuleDefinitionEntity createDefaultModule() {
        return ModuleDefinitionEntity.builder()
                .name("メンバー管理")
                .slug("member-management")
                .description("メンバー管理機能")
                .moduleType(ModuleDefinitionEntity.ModuleType.DEFAULT)
                .moduleNumber(1)
                .requiresPaidPlan(false)
                .isActive(true)
                .build();
    }

    private ModuleDefinitionEntity createPaidModule() {
        return ModuleDefinitionEntity.builder()
                .name("高度分析")
                .slug("advanced-analytics")
                .description("高度分析モジュール")
                .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                .moduleNumber(2)
                .requiresPaidPlan(true)
                .trialDays(14)
                .isActive(true)
                .build();
    }

    private TeamEnabledModuleEntity createEnabledModule(Long teamId, Long moduleId) {
        return TeamEnabledModuleEntity.builder()
                .teamId(teamId)
                .moduleId(moduleId)
                .isEnabled(true)
                .enabledAt(LocalDateTime.now())
                .enabledBy(USER_ID)
                .trialUsed(false)
                .build();
    }

    /** グランドファザリング行（is_enabled=true・is_grandfathered=true）を生成する。 */
    private TeamEnabledModuleEntity createGrandfatheredModule(Long teamId, Long moduleId) {
        return TeamEnabledModuleEntity.builder()
                .teamId(teamId)
                .moduleId(moduleId)
                .isEnabled(true)
                .isGrandfathered(true)
                .enabledAt(LocalDateTime.now())
                .enabledBy(null)
                .trialUsed(false)
                .build();
    }

    // ========================================
    // getModuleCatalog
    // ========================================

    @Nested
    @DisplayName("getModuleCatalog")
    class GetModuleCatalog {

        @Test
        @DisplayName("取得_アクティブなOPTIONALモジュールあり_リスト返却")
        void 取得_アクティブなOPTIONALモジュールあり_リスト返却() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleId(module.getId())).willReturn(List.of());
            given(moduleRecommendationRepository.findByModuleId(module.getId())).willReturn(List.of());

            // When
            List<ModuleResponse> result = moduleService.getModuleCatalog();

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("予約管理");
            assertThat(result.get(0).getModuleType()).isEqualTo("OPTIONAL");
        }

        @Test
        @DisplayName("取得_非アクティブモジュールは除外_空リスト返却")
        void 取得_非アクティブモジュールは除外_空リスト返却() {
            // Given
            ModuleDefinitionEntity inactiveModule = ModuleDefinitionEntity.builder()
                    .name("無効モジュール")
                    .slug("inactive")
                    .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                    .moduleNumber(99)
                    .requiresPaidPlan(false)
                    .isActive(false)
                    .build();
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of(inactiveModule));

            // When
            List<ModuleResponse> result = moduleService.getModuleCatalog();

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // getAllModulesForAdmin（管理一覧: DEFAULT+inactive 全件）
    // ========================================

    @Nested
    @DisplayName("getAllModulesForAdmin")
    class GetAllModulesForAdmin {

        @Test
        @DisplayName("取得_DEFAULTとinactiveを含む全件をリポジトリ順で返す")
        void 取得_DEFAULTとinactiveを含む全件を返す() {
            // Given: DEFAULT / 有効OPTIONAL / 無効OPTIONAL が moduleNumber 昇順で並ぶ
            ModuleDefinitionEntity defaultModule = ModuleDefinitionEntity.builder()
                    .name("メンバー管理").slug("member-management")
                    .moduleType(ModuleDefinitionEntity.ModuleType.DEFAULT)
                    .moduleNumber(1).requiresPaidPlan(false).isActive(true).build();
            ModuleDefinitionEntity activeOptional = ModuleDefinitionEntity.builder()
                    .name("予約管理").slug("reservation")
                    .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                    .moduleNumber(2).requiresPaidPlan(false).isActive(true).build();
            ModuleDefinitionEntity inactiveOptional = ModuleDefinitionEntity.builder()
                    .name("無効モジュール").slug("inactive")
                    .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                    .moduleNumber(3).requiresPaidPlan(false).isActive(false).build();

            given(moduleDefinitionRepository.findAllByOrderByModuleNumberAsc())
                    .willReturn(List.of(defaultModule, activeOptional, inactiveOptional));
            given(moduleLevelAvailabilityRepository.findByModuleId(any())).willReturn(List.of());
            given(moduleRecommendationRepository.findByModuleId(any())).willReturn(List.of());

            // When
            List<ModuleResponse> result = moduleService.getAllModulesForAdmin();

            // Then: DEFAULT も inactive も除外されず、リポジトリの昇順をそのまま保持する
            assertThat(result).hasSize(3);
            assertThat(result).extracting(ModuleResponse::getName)
                    .containsExactly("メンバー管理", "予約管理", "無効モジュール");
            assertThat(result).extracting(ModuleResponse::getModuleType)
                    .containsExactly("DEFAULT", "OPTIONAL", "OPTIONAL");
            assertThat(result.get(2).getIsActive()).isFalse();
        }

        @Test
        @DisplayName("取得_0件_空リスト返却")
        void 取得_0件_空リスト返却() {
            given(moduleDefinitionRepository.findAllByOrderByModuleNumberAsc()).willReturn(List.of());

            List<ModuleResponse> result = moduleService.getAllModulesForAdmin();

            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // getModule
    // ========================================

    @Nested
    @DisplayName("getModule")
    class GetModule {

        @Test
        @DisplayName("取得_存在するID_モジュール詳細返却")
        void 取得_存在するID_モジュール詳細返却() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));

            ModuleLevelAvailabilityEntity level = ModuleLevelAvailabilityEntity.builder()
                    .moduleId(MODULE_ID)
                    .level(ModuleLevelAvailabilityEntity.Level.TEAM)
                    .isAvailable(true)
                    .note("チームレベルで利用可能")
                    .build();
            given(moduleLevelAvailabilityRepository.findByModuleId(module.getId())).willReturn(List.of(level));

            ModuleRecommendationEntity rec = ModuleRecommendationEntity.builder()
                    .moduleId(MODULE_ID)
                    .recommendedModuleId(20L)
                    .reason("関連モジュール")
                    .sortOrder(1)
                    .build();
            given(moduleRecommendationRepository.findByModuleId(module.getId())).willReturn(List.of(rec));

            ModuleDefinitionEntity recModule = ModuleDefinitionEntity.builder()
                    .name("関連機能")
                    .slug("related")
                    .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                    .moduleNumber(2)
                    .requiresPaidPlan(false)
                    .isActive(true)
                    .build();
            given(moduleDefinitionRepository.findById(20L)).willReturn(Optional.of(recModule));

            // When
            ApiResponse<ModuleResponse> response = moduleService.getModule(MODULE_ID);

            // Then
            ModuleResponse data = response.getData();
            assertThat(data.getName()).isEqualTo("予約管理");
            assertThat(data.getLevelAvailability()).hasSize(1);
            assertThat(data.getLevelAvailability().get(0).getLevel()).isEqualTo("TEAM");
            assertThat(data.getLevelAvailability().get(0).getIsAvailable()).isTrue();
            assertThat(data.getRecommendations()).hasSize(1);
            assertThat(data.getRecommendations().get(0).getName()).isEqualTo("関連機能");
        }

        @Test
        @DisplayName("取得_存在しないID_TMPL002例外")
        void 取得_存在しないID_TMPL002例外() {
            // Given
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> moduleService.getModule(MODULE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_002"));
        }
    }

    // ========================================
    // getTeamModules
    // ========================================

    @Nested
    @DisplayName("getTeamModules")
    class GetTeamModules {

        @Test
        @DisplayName("取得_有効モジュールあり_レスポンスリスト返却")
        void 取得_有効モジュールあり_レスポンスリスト返却() {
            // Given
            TeamEnabledModuleEntity tem = createEnabledModule(TEAM_ID, MODULE_ID);
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(List.of(tem));

            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));

            // When
            List<TeamModuleResponse> result = moduleService.getTeamModules(TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getModuleName()).isEqualTo("予約管理");
            assertThat(result.get(0).getIsEnabled()).isTrue();
        }

        @Test
        @DisplayName("取得_モジュール定義が削除済み_nullフィルタで除外")
        void 取得_モジュール定義が削除済み_nullフィルタで除外() {
            // Given
            TeamEnabledModuleEntity tem = createEnabledModule(TEAM_ID, MODULE_ID);
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(List.of(tem));
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.empty());

            // When
            List<TeamModuleResponse> result = moduleService.getTeamModules(TEAM_ID);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("取得_有効モジュールなし_空リスト返却")
        void 取得_有効モジュールなし_空リスト返却() {
            // Given
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(List.of());

            // When
            List<TeamModuleResponse> result = moduleService.getTeamModules(TEAM_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // toggleTeamModule
    // ========================================

    @Nested
    @DisplayName("toggleTeamModule")
    class ToggleTeamModule {

        @Test
        @DisplayName("有効化_新規モジュール_TeamEnabledModule作成")
        void 有効化_新規モジュール_TeamEnabledModule作成() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.TEAM))
                    .willReturn(Optional.empty());
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(List.of());
            given(teamEnabledModuleRepository.findByTeamIdAndModuleId(TEAM_ID, MODULE_ID))
                    .willReturn(Optional.empty());
            given(teamEnabledModuleRepository.save(any(TeamEnabledModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When
            moduleService.toggleTeamModule(TEAM_ID, request, USER_ID);

            // Then
            verify(teamEnabledModuleRepository).save(any(TeamEnabledModuleEntity.class));
        }

        @Test
        @DisplayName("有効化_既存モジュール_isEnabledをtrueに更新")
        void 有効化_既存モジュール_isEnabledをtrueに更新() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.TEAM))
                    .willReturn(Optional.empty());
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(List.of());

            TeamEnabledModuleEntity existing = TeamEnabledModuleEntity.builder()
                    .teamId(TEAM_ID)
                    .moduleId(MODULE_ID)
                    .isEnabled(false)
                    .enabledBy(USER_ID)
                    .trialUsed(false)
                    .build();
            given(teamEnabledModuleRepository.findByTeamIdAndModuleId(TEAM_ID, MODULE_ID))
                    .willReturn(Optional.of(existing));
            given(teamEnabledModuleRepository.save(any(TeamEnabledModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When
            moduleService.toggleTeamModule(TEAM_ID, request, USER_ID);

            // Then: toBuilder()→id=null→INSERT化バグの回帰防止。
            // save に渡るのが findById の同一インスタンス（管理対象）であることを確認。
            ArgumentCaptor<TeamEnabledModuleEntity> captor = ArgumentCaptor.forClass(TeamEnabledModuleEntity.class);
            verify(teamEnabledModuleRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(existing);
            assertThat(captor.getValue().getIsEnabled()).isTrue();
        }

        @Test
        @DisplayName("無効化_既存モジュール_isEnabledをfalseに更新")
        void 無効化_既存モジュール_isEnabledをfalseに更新() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.TEAM))
                    .willReturn(Optional.empty());

            TeamEnabledModuleEntity existing = createEnabledModule(TEAM_ID, MODULE_ID);
            given(teamEnabledModuleRepository.findByTeamIdAndModuleId(TEAM_ID, MODULE_ID))
                    .willReturn(Optional.of(existing));
            given(teamEnabledModuleRepository.save(any(TeamEnabledModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, false);

            // When
            moduleService.toggleTeamModule(TEAM_ID, request, USER_ID);

            // Then: toBuilder()→id=null→INSERT化バグの回帰防止。
            ArgumentCaptor<TeamEnabledModuleEntity> captor = ArgumentCaptor.forClass(TeamEnabledModuleEntity.class);
            verify(teamEnabledModuleRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(existing);
            assertThat(captor.getValue().getIsEnabled()).isFalse();
            assertThat(captor.getValue().getDisabledAt()).isNotNull();
        }

        @Test
        @DisplayName("有効化_TEAMレベル不可_TMPL005例外")
        void 有効化_TEAMレベル不可_TMPL005例外() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));

            ModuleLevelAvailabilityEntity unavailable = ModuleLevelAvailabilityEntity.builder()
                    .moduleId(module.getId())
                    .level(ModuleLevelAvailabilityEntity.Level.TEAM)
                    .isAvailable(false)
                    .build();
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.TEAM))
                    .willReturn(Optional.of(unavailable));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When / Then
            assertThatThrownBy(() -> moduleService.toggleTeamModule(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_005"));
        }

        @Test
        @DisplayName("AC-C3: 有料モジュールを無権利チームで有効化_TMPL004例外（維持）")
        void 有効化_有料プラン必須で未契約_TMPL004例外() {
            // Given: F20.1 で有料判定を isEntitled(TEAM, premium) に置換。無権利=false → 従来どおり TMPL_004。
            ModuleDefinitionEntity module = createPaidModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.TEAM))
                    .willReturn(Optional.empty());
            given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, TEAM_ID,
                    FeatureKeys.TEMPLATE_PREMIUM_MODULES)).willReturn(false);

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When / Then
            assertThatThrownBy(() -> moduleService.toggleTeamModule(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_004"));
        }

        @Test
        @DisplayName("AC-C3: 有料モジュールを premium entitlement 保持チームで有効化_成功")
        void 有効化_premium権利あり_成功() {
            // Given: premium entitlement 保持（既存有料チームはブリッジで保持）→ 通過。
            ModuleDefinitionEntity module = createPaidModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.TEAM))
                    .willReturn(Optional.empty());
            given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, TEAM_ID,
                    FeatureKeys.TEMPLATE_PREMIUM_MODULES)).willReturn(true);
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(List.of());
            given(teamEnabledModuleRepository.findByTeamIdAndModuleId(TEAM_ID, MODULE_ID))
                    .willReturn(Optional.empty());
            given(teamEnabledModuleRepository.save(any(TeamEnabledModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When
            moduleService.toggleTeamModule(TEAM_ID, request, USER_ID);

            // Then: 例外なく有効化される。
            verify(teamEnabledModuleRepository).save(any(TeamEnabledModuleEntity.class));
        }

        @Test
        @DisplayName("AC-C4: premium 付与のみで有効化成功・hasPaidPlan は参照しない")
        void 有効化_premium付与のみ成功_hasPaidPlan不参照() {
            // Given: ゲートは isEntitled のみで判定し、旧 teamPlanService.hasPaidPlan には依存しない。
            ModuleDefinitionEntity module = createPaidModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.TEAM))
                    .willReturn(Optional.empty());
            given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, TEAM_ID,
                    FeatureKeys.TEMPLATE_PREMIUM_MODULES)).willReturn(true);
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(List.of());
            given(teamEnabledModuleRepository.findByTeamIdAndModuleId(TEAM_ID, MODULE_ID))
                    .willReturn(Optional.empty());
            given(teamEnabledModuleRepository.save(any(TeamEnabledModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When
            moduleService.toggleTeamModule(TEAM_ID, request, USER_ID);

            // Then
            verify(teamEnabledModuleRepository).save(any(TeamEnabledModuleEntity.class));
            verify(teamPlanService, never()).hasPaidPlan(anyLong());
        }

        @Test
        @DisplayName("有効化_無料上限10到達_TMPL003例外")
        void 有効化_無料上限10到達_TMPL003例外() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.TEAM))
                    .willReturn(Optional.empty());

            // 既に10個の有効モジュールが存在
            List<TeamEnabledModuleEntity> tenModules = IntStream.rangeClosed(1, 10)
                    .mapToObj(i -> createEnabledModule(TEAM_ID, (long) (100 + i)))
                    .toList();
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(tenModules);

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When / Then
            assertThatThrownBy(() -> moduleService.toggleTeamModule(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_003"));
        }

        @Test
        @DisplayName("有効化_モジュール不在_TMPL002例外")
        void 有効化_モジュール不在_TMPL002例外() {
            // Given
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.empty());
            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When / Then
            assertThatThrownBy(() -> moduleService.toggleTeamModule(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_002"));
        }
    }

    // ========================================
    // applyTemplate
    // ========================================

    @Nested
    @DisplayName("applyTemplate")
    class ApplyTemplate {

        @Test
        @DisplayName("適用_正常_テンプレートモジュールがチームに追加")
        void 適用_正常_テンプレートモジュールがチームに追加() {
            // Given
            TeamTemplateEntity template = TeamTemplateEntity.builder()
                    .name("テスト")
                    .slug("test")
                    .isActive(true)
                    .build();
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));

            TemplateModuleEntity tm = TemplateModuleEntity.builder()
                    .templateId(TEMPLATE_ID)
                    .moduleId(MODULE_ID)
                    .build();
            given(templateModuleRepository.findByTemplateId(TEMPLATE_ID)).willReturn(List.of(tm));
            given(teamEnabledModuleRepository.findByTeamIdAndModuleId(TEAM_ID, MODULE_ID))
                    .willReturn(Optional.empty());

            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(teamEnabledModuleRepository.save(any(TeamEnabledModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            moduleService.applyTemplate(TEAM_ID, TEMPLATE_ID, USER_ID);

            // Then
            verify(teamEnabledModuleRepository).save(any(TeamEnabledModuleEntity.class));
        }

        @Test
        @DisplayName("適用_既に有効化済みモジュール_スキップ")
        void 適用_既に有効化済みモジュール_スキップ() {
            // Given
            TeamTemplateEntity template = TeamTemplateEntity.builder()
                    .name("テスト")
                    .slug("test")
                    .isActive(true)
                    .build();
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));

            TemplateModuleEntity tm = TemplateModuleEntity.builder()
                    .templateId(TEMPLATE_ID)
                    .moduleId(MODULE_ID)
                    .build();
            given(templateModuleRepository.findByTemplateId(TEMPLATE_ID)).willReturn(List.of(tm));

            TeamEnabledModuleEntity existing = createEnabledModule(TEAM_ID, MODULE_ID);
            given(teamEnabledModuleRepository.findByTeamIdAndModuleId(TEAM_ID, MODULE_ID))
                    .willReturn(Optional.of(existing));

            // When
            moduleService.applyTemplate(TEAM_ID, TEMPLATE_ID, USER_ID);

            // Then
            verify(teamEnabledModuleRepository, never()).save(any(TeamEnabledModuleEntity.class));
        }

        @Test
        @DisplayName("適用_モジュール定義不在_スキップ")
        void 適用_モジュール定義不在_スキップ() {
            // Given
            TeamTemplateEntity template = TeamTemplateEntity.builder()
                    .name("テスト")
                    .slug("test")
                    .isActive(true)
                    .build();
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));

            TemplateModuleEntity tm = TemplateModuleEntity.builder()
                    .templateId(TEMPLATE_ID)
                    .moduleId(MODULE_ID)
                    .build();
            given(templateModuleRepository.findByTemplateId(TEMPLATE_ID)).willReturn(List.of(tm));
            given(teamEnabledModuleRepository.findByTeamIdAndModuleId(TEAM_ID, MODULE_ID))
                    .willReturn(Optional.empty());
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.empty());

            // When
            moduleService.applyTemplate(TEAM_ID, TEMPLATE_ID, USER_ID);

            // Then
            verify(teamEnabledModuleRepository, never()).save(any(TeamEnabledModuleEntity.class));
        }

        @Test
        @DisplayName("適用_テンプレート不在_TMPL001例外")
        void 適用_テンプレート不在_TMPL001例外() {
            // Given
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> moduleService.applyTemplate(TEAM_ID, TEMPLATE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_001"));
        }
    }

    // ========================================
    // isModuleEnabledForTeam
    // ========================================

    @Nested
    @DisplayName("isModuleEnabledForTeam")
    class IsModuleEnabledForTeam {

        @Test
        @DisplayName("判定_DEFAULTモジュール_常にtrue")
        void 判定_DEFAULTモジュール_常にtrue() {
            // Given
            ModuleDefinitionEntity module = createDefaultModule();
            given(moduleDefinitionRepository.findBySlug("member-management"))
                    .willReturn(Optional.of(module));

            // When
            boolean result = moduleService.isModuleEnabledForTeam("member-management", TEAM_ID);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("判定_OPTIONALモジュールで有効化済み_true")
        void 判定_OPTIONALモジュールで有効化済み_true() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findBySlug("reservation"))
                    .willReturn(Optional.of(module));

            TeamEnabledModuleEntity tem = createEnabledModule(TEAM_ID, module.getId());
            given(teamEnabledModuleRepository.findByTeamIdAndModuleId(TEAM_ID, module.getId()))
                    .willReturn(Optional.of(tem));

            // When
            boolean result = moduleService.isModuleEnabledForTeam("reservation", TEAM_ID);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("判定_OPTIONALモジュールで未有効化_false")
        void 判定_OPTIONALモジュールで未有効化_false() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findBySlug("reservation"))
                    .willReturn(Optional.of(module));
            given(teamEnabledModuleRepository.findByTeamIdAndModuleId(TEAM_ID, module.getId()))
                    .willReturn(Optional.empty());

            // When
            boolean result = moduleService.isModuleEnabledForTeam("reservation", TEAM_ID);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("判定_モジュール不在_false")
        void 判定_モジュール不在_false() {
            // Given
            given(moduleDefinitionRepository.findBySlug("nonexistent"))
                    .willReturn(Optional.empty());

            // When
            boolean result = moduleService.isModuleEnabledForTeam("nonexistent", TEAM_ID);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("判定_非アクティブモジュール_false")
        void 判定_非アクティブモジュール_false() {
            // Given
            ModuleDefinitionEntity module = ModuleDefinitionEntity.builder()
                    .name("無効モジュール")
                    .slug("inactive")
                    .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                    .moduleNumber(99)
                    .requiresPaidPlan(false)
                    .isActive(false)
                    .build();
            given(moduleDefinitionRepository.findBySlug("inactive"))
                    .willReturn(Optional.of(module));

            // When
            boolean result = moduleService.isModuleEnabledForTeam("inactive", TEAM_ID);

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // getModuleDisabledReason
    // ========================================

    @Nested
    @DisplayName("getModuleDisabledReason")
    class GetModuleDisabledReason {

        @Test
        @DisplayName("判定_モジュール不在_存在しないメッセージ返却")
        void 判定_モジュール不在_存在しないメッセージ返却() {
            // Given
            given(moduleDefinitionRepository.findBySlug("nonexistent"))
                    .willReturn(Optional.empty());

            // When
            String reason = moduleService.getModuleDisabledReason("nonexistent", TEAM_ID);

            // Then
            assertThat(reason).isEqualTo("モジュールが存在しません");
        }

        @Test
        @DisplayName("判定_非アクティブモジュール_無効化メッセージ返却")
        void 判定_非アクティブモジュール_無効化メッセージ返却() {
            // Given
            ModuleDefinitionEntity module = ModuleDefinitionEntity.builder()
                    .name("無効モジュール")
                    .slug("inactive")
                    .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                    .moduleNumber(99)
                    .requiresPaidPlan(false)
                    .isActive(false)
                    .build();
            given(moduleDefinitionRepository.findBySlug("inactive"))
                    .willReturn(Optional.of(module));

            // When
            String reason = moduleService.getModuleDisabledReason("inactive", TEAM_ID);

            // Then
            assertThat(reason).isEqualTo("モジュールが無効化されています");
        }

        @Test
        @DisplayName("判定_DEFAULTモジュール_null返却")
        void 判定_DEFAULTモジュール_null返却() {
            // Given
            ModuleDefinitionEntity module = createDefaultModule();
            given(moduleDefinitionRepository.findBySlug("member-management"))
                    .willReturn(Optional.of(module));

            // When
            String reason = moduleService.getModuleDisabledReason("member-management", TEAM_ID);

            // Then
            assertThat(reason).isNull();
        }

        // Note: "OPTIONAL + enabled → null" case requires integration test
        // because BaseEntity.id cannot be set via builder, causing mock argument mismatch.

        @Test
        @DisplayName("判定_OPTIONALモジュールで未有効化_未有効化メッセージ返却")
        void 判定_OPTIONALモジュールで未有効化_未有効化メッセージ返却() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findBySlug("reservation"))
                    .willReturn(Optional.of(module));
            given(teamEnabledModuleRepository.findByTeamIdAndModuleId(TEAM_ID, module.getId()))
                    .willReturn(Optional.empty());

            // When
            String reason = moduleService.getModuleDisabledReason("reservation", TEAM_ID);

            // Then
            assertThat(reason).isEqualTo("このチームでは未有効化です");
        }
    }

    // ========================================
    // toggleOrganizationModule（回帰テスト込み）
    // ========================================

    @Nested
    @DisplayName("toggleOrganizationModule")
    class ToggleOrganizationModule {

        private static final Long ORG_ID = 200L;

        @Test
        @DisplayName("有効化_既存エンティティあり_同一インスタンスで保存_INSERT化バグ回帰防止")
        void 有効化_既存エンティティあり_同一インスタンスで保存() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.ORGANIZATION))
                    .willReturn(Optional.empty());
            given(organizationEnabledModuleRepository.countByOrganizationIdAndIsEnabledTrueAndIsGrandfatheredFalse(ORG_ID))
                    .willReturn(0L);

            OrganizationEnabledModuleEntity existing = OrganizationEnabledModuleEntity.builder()
                    .organizationId(ORG_ID)
                    .moduleId(MODULE_ID)
                    .isEnabled(false)
                    .enabledBy(USER_ID)
                    .build();
            given(organizationEnabledModuleRepository.findByOrganizationIdAndModuleId(ORG_ID, MODULE_ID))
                    .willReturn(Optional.of(existing));
            given(organizationEnabledModuleRepository.save(any(OrganizationEnabledModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When
            moduleService.toggleOrganizationModule(ORG_ID, request, USER_ID);

            // Then: toBuilder()→id=null→INSERT化バグの回帰防止。
            // save に渡るのが findById の同一インスタンス（管理対象）であることを確認。
            ArgumentCaptor<OrganizationEnabledModuleEntity> captor =
                    ArgumentCaptor.forClass(OrganizationEnabledModuleEntity.class);
            verify(organizationEnabledModuleRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(existing);
            assertThat(captor.getValue().getIsEnabled()).isTrue();
        }

        @Test
        @DisplayName("無効化_既存エンティティあり_同一インスタンスで保存かつdisabledAt設定")
        void 無効化_既存エンティティあり_同一インスタンスで保存かつdisabledAt設定() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.ORGANIZATION))
                    .willReturn(Optional.empty());

            OrganizationEnabledModuleEntity existing = OrganizationEnabledModuleEntity.builder()
                    .organizationId(ORG_ID)
                    .moduleId(MODULE_ID)
                    .isEnabled(true)
                    .enabledAt(LocalDateTime.now().minusDays(1))
                    .enabledBy(USER_ID)
                    .build();
            given(organizationEnabledModuleRepository.findByOrganizationIdAndModuleId(ORG_ID, MODULE_ID))
                    .willReturn(Optional.of(existing));
            given(organizationEnabledModuleRepository.save(any(OrganizationEnabledModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, false);

            // When
            moduleService.toggleOrganizationModule(ORG_ID, request, USER_ID);

            // Then
            ArgumentCaptor<OrganizationEnabledModuleEntity> captor =
                    ArgumentCaptor.forClass(OrganizationEnabledModuleEntity.class);
            verify(organizationEnabledModuleRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(existing);
            assertThat(captor.getValue().getIsEnabled()).isFalse();
            assertThat(captor.getValue().getDisabledAt()).isNotNull();
        }

        @Test
        @DisplayName("有効化_DEFAULTモジュール_TMPL006例外")
        void 有効化_DEFAULTモジュール_TMPL006例外() {
            // Given
            ModuleDefinitionEntity module = createDefaultModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When / Then
            assertThatThrownBy(() -> moduleService.toggleOrganizationModule(ORG_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_006"));
        }

        @Test
        @DisplayName("有効化_ORGANIZATIONレベル不可_TMPL005例外")
        void 有効化_ORGANIZATIONレベル不可_TMPL005例外() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));

            ModuleLevelAvailabilityEntity unavailable = ModuleLevelAvailabilityEntity.builder()
                    .moduleId(module.getId())
                    .level(ModuleLevelAvailabilityEntity.Level.ORGANIZATION)
                    .isAvailable(false)
                    .build();
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.ORGANIZATION))
                    .willReturn(Optional.of(unavailable));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When / Then
            assertThatThrownBy(() -> moduleService.toggleOrganizationModule(ORG_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_005"));
        }

        @Test
        @DisplayName("有効化_無料上限10到達_TMPL003例外")
        void 有効化_無料上限10到達_TMPL003例外() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.ORGANIZATION))
                    .willReturn(Optional.empty());
            given(organizationEnabledModuleRepository.countByOrganizationIdAndIsEnabledTrueAndIsGrandfatheredFalse(ORG_ID))
                    .willReturn(10L);

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When / Then
            assertThatThrownBy(() -> moduleService.toggleOrganizationModule(ORG_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_003"));
        }

        @Test
        @DisplayName("AC-6: 有料モジュールを premium 権利なし組織で有効化_TMPL004例外（穴の根治）")
        void 有効化_有料モジュールでpremium権利なし_TMPL004例外() {
            // Given: 組織側にもチーム側と対称の有料ゲートを敷設。scope=ORG の premium entitlement が
            // 無ければ TMPL_004。以前は組織側にゲートが無く entitlement 無しでも有効化できる穴だった。
            ModuleDefinitionEntity module = createPaidModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.ORGANIZATION))
                    .willReturn(Optional.empty());
            given(entitlementQueryService.isEntitled(EntitlementScopeKind.ORG, ORG_ID,
                    FeatureKeys.TEMPLATE_PREMIUM_MODULES)).willReturn(false);

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When / Then
            assertThatThrownBy(() -> moduleService.toggleOrganizationModule(ORG_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_004"));
        }

        @Test
        @DisplayName("AC-6: 有料モジュールを premium 権利あり組織で有効化_成功")
        void 有効化_有料モジュールでpremium権利あり_成功() {
            // Given: scope=ORG の premium entitlement 保持 → ゲート通過し有効化される。
            ModuleDefinitionEntity module = createPaidModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.ORGANIZATION))
                    .willReturn(Optional.empty());
            given(entitlementQueryService.isEntitled(EntitlementScopeKind.ORG, ORG_ID,
                    FeatureKeys.TEMPLATE_PREMIUM_MODULES)).willReturn(true);
            given(organizationEnabledModuleRepository.countByOrganizationIdAndIsEnabledTrueAndIsGrandfatheredFalse(ORG_ID))
                    .willReturn(0L);
            given(organizationEnabledModuleRepository.findByOrganizationIdAndModuleId(ORG_ID, MODULE_ID))
                    .willReturn(Optional.empty());
            given(organizationEnabledModuleRepository.save(any(OrganizationEnabledModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When
            moduleService.toggleOrganizationModule(ORG_ID, request, USER_ID);

            // Then: 例外なく有効化される。
            verify(organizationEnabledModuleRepository).save(any(OrganizationEnabledModuleEntity.class));
        }

        @Test
        @DisplayName("AC-6: 無料モジュールは有料ゲートを通らず entitlement 未参照で有効化成功")
        void 有効化_無料モジュールは有料ゲート不通過_成功() {
            // Given: requiresPaidPlan=false なら短絡評価で isEntitled は呼ばれない（回帰防止）。
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.ORGANIZATION))
                    .willReturn(Optional.empty());
            given(organizationEnabledModuleRepository.countByOrganizationIdAndIsEnabledTrueAndIsGrandfatheredFalse(ORG_ID))
                    .willReturn(0L);
            given(organizationEnabledModuleRepository.findByOrganizationIdAndModuleId(ORG_ID, MODULE_ID))
                    .willReturn(Optional.empty());
            given(organizationEnabledModuleRepository.save(any(OrganizationEnabledModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When
            moduleService.toggleOrganizationModule(ORG_ID, request, USER_ID);

            // Then: 有効化され、無料モジュールでは entitlement 判定は呼ばれない。
            verify(organizationEnabledModuleRepository).save(any(OrganizationEnabledModuleEntity.class));
            verify(entitlementQueryService, never())
                    .isEntitled(any(EntitlementScopeKind.class), anyLong(), any(String.class));
        }
    }

    // ========================================
    // isModuleEnabledForOrg
    // ========================================

    @Nested
    @DisplayName("isModuleEnabledForOrg")
    class IsModuleEnabledForOrg {

        private static final Long ORG_ID = 200L;

        @Test
        @DisplayName("判定_DEFAULTモジュール_常にtrue")
        void 判定_DEFAULTモジュール_常にtrue() {
            // Given
            ModuleDefinitionEntity module = createDefaultModule();
            given(moduleDefinitionRepository.findBySlug("member-management"))
                    .willReturn(Optional.of(module));

            // When
            boolean result = moduleService.isModuleEnabledForOrg("member-management", ORG_ID);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("判定_OPTIONALモジュールで有効化済み_true")
        void 判定_OPTIONALモジュールで有効化済み_true() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findBySlug("reservation"))
                    .willReturn(Optional.of(module));

            OrganizationEnabledModuleEntity oem = OrganizationEnabledModuleEntity.builder()
                    .organizationId(ORG_ID)
                    .moduleId(module.getId())
                    .isEnabled(true)
                    .build();
            given(organizationEnabledModuleRepository.findByOrganizationIdAndModuleId(ORG_ID, module.getId()))
                    .willReturn(Optional.of(oem));

            // When
            boolean result = moduleService.isModuleEnabledForOrg("reservation", ORG_ID);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("判定_OPTIONALモジュールで未有効化_false")
        void 判定_OPTIONALモジュールで未有効化_false() {
            // Given
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findBySlug("reservation"))
                    .willReturn(Optional.of(module));
            given(organizationEnabledModuleRepository.findByOrganizationIdAndModuleId(ORG_ID, module.getId()))
                    .willReturn(Optional.empty());

            // When
            boolean result = moduleService.isModuleEnabledForOrg("reservation", ORG_ID);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("判定_モジュール不在_false")
        void 判定_モジュール不在_false() {
            // Given
            given(moduleDefinitionRepository.findBySlug("nonexistent"))
                    .willReturn(Optional.empty());

            // When
            boolean result = moduleService.isModuleEnabledForOrg("nonexistent", ORG_ID);

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // 無料上限グランドファザリング除外（PR-A 難所の算術）
    // ========================================

    @Nested
    @DisplayName("無料上限からのグランドファザリング除外")
    class GrandfatherLimitExclusion {

        private static final Long ORG_ID = 200L;

        @Test
        @DisplayName("チーム: グランドファザリング7本があっても通常10本で上限到達し11本目でTMPL003")
        void チーム_グランドファザリング除外_通常10本で上限到達() {
            // Given: 通常有効10本 + グランドファザリング7本 = 計17行。
            // 上限カウントは grandfather を除外して 10 → 11本目（今回のトグル）で TMPL_003。
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.TEAM))
                    .willReturn(Optional.empty());

            List<TeamEnabledModuleEntity> rows = new java.util.ArrayList<>();
            IntStream.rangeClosed(1, 10)
                    .forEach(i -> rows.add(createEnabledModule(TEAM_ID, (long) (100 + i))));
            IntStream.rangeClosed(1, 7)
                    .forEach(i -> rows.add(createGrandfatheredModule(TEAM_ID, (long) (200 + i))));
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(rows);

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When / Then
            assertThatThrownBy(() -> moduleService.toggleTeamModule(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_003"));
        }

        @Test
        @DisplayName("チーム: グランドファザリングのみ7本（通常0）なら上限に達さず有効化成功")
        void チーム_グランドファザリングのみは上限未達_有効化成功() {
            // Given: grandfather 7本のみ（通常0）。上限カウント=0 → 有効化できる。
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.TEAM))
                    .willReturn(Optional.empty());

            List<TeamEnabledModuleEntity> rows = IntStream.rangeClosed(1, 7)
                    .mapToObj(i -> createGrandfatheredModule(TEAM_ID, (long) (200 + i)))
                    .collect(java.util.stream.Collectors.toList());
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(rows);
            given(teamEnabledModuleRepository.findByTeamIdAndModuleId(TEAM_ID, MODULE_ID))
                    .willReturn(Optional.empty());
            given(teamEnabledModuleRepository.save(any(TeamEnabledModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When
            moduleService.toggleTeamModule(TEAM_ID, request, USER_ID);

            // Then: 上限に達さず有効化される。
            verify(teamEnabledModuleRepository).save(any(TeamEnabledModuleEntity.class));
        }

        @Test
        @DisplayName("組織: 上限判定はグランドファザリング除外メソッドを使う（10で上限到達しTMPL003）")
        void 組織_グランドファザリング除外メソッドで上限到達() {
            // Given: 除外後カウント=10（grandfather はDB側で除外済み）→ TMPL_003。
            // 除外していない旧 countByOrganizationIdAndIsEnabledTrue は呼ばれてはならない。
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.ORGANIZATION))
                    .willReturn(Optional.empty());
            given(organizationEnabledModuleRepository
                    .countByOrganizationIdAndIsEnabledTrueAndIsGrandfatheredFalse(ORG_ID))
                    .willReturn(10L);

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When / Then
            assertThatThrownBy(() -> moduleService.toggleOrganizationModule(ORG_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_003"));
            verify(organizationEnabledModuleRepository, never())
                    .countByOrganizationIdAndIsEnabledTrue(anyLong());
        }

        @Test
        @DisplayName("組織: 除外後カウント0（grandfitherのみ相当）なら上限未達で有効化成功")
        void 組織_除外後0なら上限未達_有効化成功() {
            // Given: 除外後カウント=0（有効行が grandfather のみでDB側で0に落ちた相当）→ 有効化できる。
            ModuleDefinitionEntity module = createOptionalModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.ORGANIZATION))
                    .willReturn(Optional.empty());
            given(organizationEnabledModuleRepository
                    .countByOrganizationIdAndIsEnabledTrueAndIsGrandfatheredFalse(ORG_ID))
                    .willReturn(0L);
            given(organizationEnabledModuleRepository.findByOrganizationIdAndModuleId(ORG_ID, MODULE_ID))
                    .willReturn(Optional.empty());
            given(organizationEnabledModuleRepository.save(any(OrganizationEnabledModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When
            moduleService.toggleOrganizationModule(ORG_ID, request, USER_ID);

            // Then
            verify(organizationEnabledModuleRepository).save(any(OrganizationEnabledModuleEntity.class));
            verify(organizationEnabledModuleRepository, never())
                    .countByOrganizationIdAndIsEnabledTrue(anyLong());
        }
    }

    // ========================================
    // カタログ表示用 enabledCount のグランドファザリング除外
    // （FE「X/10 使用中」表示・追加ボタン活性判定と上限強制の整合）
    // ========================================

    @Nested
    @DisplayName("カタログ enabledCount のグランドファザリング除外")
    class CatalogEnabledCountExclusion {

        private static final Long ORG_ID = 200L;

        @Test
        @DisplayName("チームカタログ: enabledCount は grandfather を除外し通常有効化分のみ（3本＋grandfather5本→3）")
        void チームカタログ_enabledCountはgrandfather除外() {
            // Given: 通常有効3本 + grandfather5本。表示用 enabledCount は grandfather を数えず 3。
            List<TeamEnabledModuleEntity> rows = new java.util.ArrayList<>();
            IntStream.rangeClosed(1, 3)
                    .forEach(i -> rows.add(createEnabledModule(TEAM_ID, (long) (10 + i))));
            IntStream.rangeClosed(1, 5)
                    .forEach(i -> rows.add(createGrandfatheredModule(TEAM_ID, (long) (50 + i))));
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(rows);
            // カタログ母集合は本テストの関心外なので空にする（enabledCount は enable 行から算出される）。
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of());

            // When
            TeamModuleCatalogResponse resp = moduleService.getTeamModuleCatalog(TEAM_ID);

            // Then: grandfather を除外した通常有効化分のみが使用数として表示される。
            assertThat(resp.getEnabledCount())
                    .as("grandfather 行は使用数に数えない")
                    .isEqualTo(3L);
            assertThat(resp.getPlanLimit()).isEqualTo(10);
        }

        @Test
        @DisplayName("チームカタログ: grandfather のみ（通常0）なら enabledCount=0")
        void チームカタログ_grandfatherのみはenabledCount0() {
            List<TeamEnabledModuleEntity> rows = IntStream.rangeClosed(1, 7)
                    .mapToObj(i -> createGrandfatheredModule(TEAM_ID, (long) (50 + i)))
                    .collect(java.util.stream.Collectors.toList());
            given(teamEnabledModuleRepository.findByTeamId(TEAM_ID)).willReturn(rows);
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of());

            TeamModuleCatalogResponse resp = moduleService.getTeamModuleCatalog(TEAM_ID);

            assertThat(resp.getEnabledCount())
                    .as("grandfather のみなら使用数は 0（既得機能は枠を消費しない）")
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("組織カタログ: enabledCount は grandfather を除外し通常有効化分のみ（4本＋grandfather7本→4）")
        void 組織カタログ_enabledCountはgrandfather除外() {
            // Given: 通常有効4本 + grandfather7本。表示用 enabledCount は grandfather を数えず 4。
            List<OrganizationEnabledModuleEntity> rows = new java.util.ArrayList<>();
            IntStream.rangeClosed(1, 4)
                    .forEach(i -> rows.add(OrganizationEnabledModuleEntity.builder()
                            .organizationId(ORG_ID).moduleId((long) (10 + i))
                            .isEnabled(true).enabledBy(USER_ID).build()));
            IntStream.rangeClosed(1, 7)
                    .forEach(i -> rows.add(OrganizationEnabledModuleEntity.builder()
                            .organizationId(ORG_ID).moduleId((long) (50 + i))
                            .isEnabled(true).isGrandfathered(true).enabledBy(null).build()));
            given(organizationEnabledModuleRepository.findByOrganizationId(ORG_ID)).willReturn(rows);
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of());

            // When
            OrgModuleCatalogResponse resp = moduleService.getOrganizationModuleCatalog(ORG_ID);

            // Then
            assertThat(resp.getEnabledCount())
                    .as("grandfather 行は使用数に数えない")
                    .isEqualTo(4L);
            assertThat(resp.getPlanLimit()).isEqualTo(10);
        }

        @Test
        @DisplayName("組織カタログ: grandfather のみ（通常0）なら enabledCount=0")
        void 組織カタログ_grandfatherのみはenabledCount0() {
            List<OrganizationEnabledModuleEntity> rows = IntStream.rangeClosed(1, 7)
                    .mapToObj(i -> OrganizationEnabledModuleEntity.builder()
                            .organizationId(ORG_ID).moduleId((long) (50 + i))
                            .isEnabled(true).isGrandfathered(true).enabledBy(null).build())
                    .collect(java.util.stream.Collectors.toList());
            given(organizationEnabledModuleRepository.findByOrganizationId(ORG_ID)).willReturn(rows);
            given(moduleDefinitionRepository.findByModuleType(ModuleDefinitionEntity.ModuleType.OPTIONAL))
                    .willReturn(List.of());

            OrgModuleCatalogResponse resp = moduleService.getOrganizationModuleCatalog(ORG_ID);

            assertThat(resp.getEnabledCount())
                    .as("grandfather のみなら使用数は 0（既得機能は枠を消費しない）")
                    .isEqualTo(0L);
        }
    }
}
