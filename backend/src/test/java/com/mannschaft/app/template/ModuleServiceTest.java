package com.mannschaft.app.template;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.service.TeamPlanService;
import com.mannschaft.app.template.dto.ModuleResponse;
import com.mannschaft.app.template.dto.TeamModuleResponse;
import com.mannschaft.app.template.dto.ToggleModuleRequest;
import com.mannschaft.app.template.entity.ModuleDefinitionEntity;
import com.mannschaft.app.template.entity.ModuleLevelAvailabilityEntity;
import com.mannschaft.app.template.entity.ModuleRecommendationEntity;
import com.mannschaft.app.template.entity.TeamEnabledModuleEntity;
import com.mannschaft.app.template.entity.TeamTemplateEntity;
import com.mannschaft.app.template.entity.TemplateModuleEntity;
import com.mannschaft.app.template.repository.ModuleDefinitionRepository;
import com.mannschaft.app.template.repository.ModuleLevelAvailabilityRepository;
import com.mannschaft.app.template.repository.ModuleRecommendationRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
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
    private TemplateModuleRepository templateModuleRepository;

    @Mock
    private TeamTemplateRepository teamTemplateRepository;

    @Mock
    private TeamPlanService teamPlanService;

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

            // Then
            verify(teamEnabledModuleRepository).save(any(TeamEnabledModuleEntity.class));
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

            // Then
            verify(teamEnabledModuleRepository).save(any(TeamEnabledModuleEntity.class));
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
        @DisplayName("有効化_有料プラン必須で未契約_TMPL004例外")
        void 有効化_有料プラン必須で未契約_TMPL004例外() {
            // Given
            ModuleDefinitionEntity module = createPaidModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    module.getId(), ModuleLevelAvailabilityEntity.Level.TEAM))
                    .willReturn(Optional.empty());
            given(teamPlanService.hasPaidPlan(TEAM_ID)).willReturn(false);

            ToggleModuleRequest request = new ToggleModuleRequest(MODULE_ID, true);

            // When / Then
            assertThatThrownBy(() -> moduleService.toggleTeamModule(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_004"));
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
}
