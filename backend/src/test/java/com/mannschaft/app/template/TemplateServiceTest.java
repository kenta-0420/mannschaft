package com.mannschaft.app.template;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.template.dto.ModuleSummaryResponse;
import com.mannschaft.app.template.dto.TemplateResponse;
import com.mannschaft.app.template.dto.TemplateSummaryResponse;
import com.mannschaft.app.template.entity.ModuleDefinitionEntity;
import com.mannschaft.app.template.entity.TeamTemplateEntity;
import com.mannschaft.app.template.entity.TemplateModuleEntity;
import com.mannschaft.app.template.repository.ModuleDefinitionRepository;
import com.mannschaft.app.template.repository.TeamTemplateRepository;
import com.mannschaft.app.template.repository.TemplateModuleRepository;
import com.mannschaft.app.template.service.TemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link TemplateService} の単体テスト。
 * テンプレート一覧取得・詳細取得・モジュール一覧取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TemplateService 単体テスト")
class TemplateServiceTest {

    @Mock
    private TeamTemplateRepository teamTemplateRepository;

    @Mock
    private TemplateModuleRepository templateModuleRepository;

    @Mock
    private ModuleDefinitionRepository moduleDefinitionRepository;

    @InjectMocks
    private TemplateService templateService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEMPLATE_ID = 1L;
    private static final Long MODULE_ID_1 = 10L;
    private static final Long MODULE_ID_2 = 20L;

    private TeamTemplateEntity createTemplate() {
        return TeamTemplateEntity.builder()
                .name("スポーツチーム")
                .slug("sports-team")
                .description("スポーツチーム向けテンプレート")
                .iconUrl("https://example.com/icon.png")
                .category("sports")
                .isActive(true)
                .createdBy(1L)
                .build();
    }

    private TemplateModuleEntity createTemplateModule(Long templateId, Long moduleId) {
        return TemplateModuleEntity.builder()
                .templateId(templateId)
                .moduleId(moduleId)
                .build();
    }

    private ModuleDefinitionEntity createModule(Long id, String name, String slug) {
        return ModuleDefinitionEntity.builder()
                .name(name)
                .slug(slug)
                .description(name + "の説明")
                .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                .moduleNumber(1)
                .requiresPaidPlan(false)
                .isActive(true)
                .build();
    }

    // ========================================
    // getTemplates
    // ========================================

    @Nested
    @DisplayName("getTemplates")
    class GetTemplates {

        @Test
        @DisplayName("取得_アクティブテンプレートあり_サマリーリスト返却")
        void 取得_アクティブテンプレートあり_サマリーリスト返却() {
            // Given
            TeamTemplateEntity template = createTemplate();
            given(teamTemplateRepository.findByIsActiveTrue()).willReturn(List.of(template));
            given(templateModuleRepository.findByTemplateId(template.getId()))
                    .willReturn(List.of(
                            createTemplateModule(template.getId(), MODULE_ID_1),
                            createTemplateModule(template.getId(), MODULE_ID_2)));

            // When
            List<TemplateSummaryResponse> result = templateService.getTemplates();

            // Then
            assertThat(result).hasSize(1);
            TemplateSummaryResponse summary = result.get(0);
            assertThat(summary.getName()).isEqualTo("スポーツチーム");
            assertThat(summary.getSlug()).isEqualTo("sports-team");
            assertThat(summary.getCategory()).isEqualTo("sports");
            assertThat(summary.getModuleCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("取得_アクティブテンプレートなし_空リスト返却")
        void 取得_アクティブテンプレートなし_空リスト返却() {
            // Given
            given(teamTemplateRepository.findByIsActiveTrue()).willReturn(List.of());

            // When
            List<TemplateSummaryResponse> result = templateService.getTemplates();

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // getTemplate
    // ========================================

    @Nested
    @DisplayName("getTemplate")
    class GetTemplate {

        @Test
        @DisplayName("取得_存在するID_テンプレート詳細返却")
        void 取得_存在するID_テンプレート詳細返却() {
            // Given
            TeamTemplateEntity template = createTemplate();
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));

            TemplateModuleEntity tm = createTemplateModule(TEMPLATE_ID, MODULE_ID_1);
            given(templateModuleRepository.findByTemplateId(TEMPLATE_ID)).willReturn(List.of(tm));

            ModuleDefinitionEntity module = createModule(MODULE_ID_1, "予約管理", "reservation");
            given(moduleDefinitionRepository.findById(MODULE_ID_1)).willReturn(Optional.of(module));

            // When
            ApiResponse<TemplateResponse> response = templateService.getTemplate(TEMPLATE_ID);

            // Then
            TemplateResponse data = response.getData();
            assertThat(data.getName()).isEqualTo("スポーツチーム");
            assertThat(data.getSlug()).isEqualTo("sports-team");
            assertThat(data.getDescription()).isEqualTo("スポーツチーム向けテンプレート");
            assertThat(data.getIsActive()).isTrue();
            assertThat(data.getModules()).hasSize(1);
            assertThat(data.getModules().get(0).getName()).isEqualTo("予約管理");
        }

        @Test
        @DisplayName("取得_存在しないID_TMPL001例外")
        void 取得_存在しないID_TMPL001例外() {
            // Given
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> templateService.getTemplate(TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_001"));
        }

        @Test
        @DisplayName("取得_モジュール定義が存在しない紐付き_nullフィルタで除外")
        void 取得_モジュール定義が存在しない紐付き_nullフィルタで除外() {
            // Given
            TeamTemplateEntity template = createTemplate();
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));

            TemplateModuleEntity tm = createTemplateModule(TEMPLATE_ID, MODULE_ID_1);
            given(templateModuleRepository.findByTemplateId(TEMPLATE_ID)).willReturn(List.of(tm));
            given(moduleDefinitionRepository.findById(MODULE_ID_1)).willReturn(Optional.empty());

            // When
            ApiResponse<TemplateResponse> response = templateService.getTemplate(TEMPLATE_ID);

            // Then
            assertThat(response.getData().getModules()).isEmpty();
        }
    }

    // ========================================
    // getTemplateModules
    // ========================================

    @Nested
    @DisplayName("getTemplateModules")
    class GetTemplateModules {

        @Test
        @DisplayName("取得_存在するテンプレート_モジュールサマリーリスト返却")
        void 取得_存在するテンプレート_モジュールサマリーリスト返却() {
            // Given
            TeamTemplateEntity template = createTemplate();
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));

            TemplateModuleEntity tm1 = createTemplateModule(TEMPLATE_ID, MODULE_ID_1);
            TemplateModuleEntity tm2 = createTemplateModule(TEMPLATE_ID, MODULE_ID_2);
            given(templateModuleRepository.findByTemplateId(TEMPLATE_ID)).willReturn(List.of(tm1, tm2));

            ModuleDefinitionEntity module1 = createModule(MODULE_ID_1, "予約管理", "reservation");
            ModuleDefinitionEntity module2 = createModule(MODULE_ID_2, "掲示板", "bulletin");
            given(moduleDefinitionRepository.findById(MODULE_ID_1)).willReturn(Optional.of(module1));
            given(moduleDefinitionRepository.findById(MODULE_ID_2)).willReturn(Optional.of(module2));

            // When
            List<ModuleSummaryResponse> result = templateService.getTemplateModules(TEMPLATE_ID);

            // Then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("予約管理");
            assertThat(result.get(1).getName()).isEqualTo("掲示板");
        }

        @Test
        @DisplayName("取得_存在しないテンプレート_TMPL001例外")
        void 取得_存在しないテンプレート_TMPL001例外() {
            // Given
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> templateService.getTemplateModules(TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_001"));
        }

        @Test
        @DisplayName("取得_モジュール紐付きなし_空リスト返却")
        void 取得_モジュール紐付きなし_空リスト返却() {
            // Given
            TeamTemplateEntity template = createTemplate();
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));
            given(templateModuleRepository.findByTemplateId(TEMPLATE_ID)).willReturn(List.of());

            // When
            List<ModuleSummaryResponse> result = templateService.getTemplateModules(TEMPLATE_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
