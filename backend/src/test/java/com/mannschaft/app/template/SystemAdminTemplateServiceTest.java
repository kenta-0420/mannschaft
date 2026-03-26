package com.mannschaft.app.template;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.template.dto.CreateTemplateRequest;
import com.mannschaft.app.template.dto.TemplateResponse;
import com.mannschaft.app.template.dto.UpdateLevelAvailabilityRequest;
import com.mannschaft.app.template.dto.UpdateTemplateRequest;
import com.mannschaft.app.template.entity.ModuleDefinitionEntity;
import com.mannschaft.app.template.entity.ModuleLevelAvailabilityEntity;
import com.mannschaft.app.template.entity.TeamTemplateEntity;
import com.mannschaft.app.template.entity.TemplateModuleEntity;
import com.mannschaft.app.template.repository.ModuleDefinitionRepository;
import com.mannschaft.app.template.repository.ModuleLevelAvailabilityRepository;
import com.mannschaft.app.template.repository.TeamTemplateRepository;
import com.mannschaft.app.template.repository.TemplateModuleRepository;
import com.mannschaft.app.template.service.SystemAdminTemplateService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SystemAdminTemplateService} の単体テスト。
 * テンプレートCRUD・レベル別利用可否更新を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SystemAdminTemplateService 単体テスト")
class SystemAdminTemplateServiceTest {

    @Mock
    private TeamTemplateRepository teamTemplateRepository;

    @Mock
    private TemplateModuleRepository templateModuleRepository;

    @Mock
    private ModuleDefinitionRepository moduleDefinitionRepository;

    @Mock
    private ModuleLevelAvailabilityRepository moduleLevelAvailabilityRepository;

    @InjectMocks
    private SystemAdminTemplateService systemAdminTemplateService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEMPLATE_ID = 1L;
    private static final Long MODULE_ID = 10L;
    private static final Long USER_ID = 100L;

    private TeamTemplateEntity createTemplate() {
        return TeamTemplateEntity.builder()
                .name("スポーツチーム")
                .slug("sports-team")
                .description("スポーツチーム向けテンプレート")
                .iconUrl("https://example.com/icon.png")
                .category("sports")
                .isActive(true)
                .createdBy(USER_ID)
                .build();
    }

    private ModuleDefinitionEntity createModule() {
        return ModuleDefinitionEntity.builder()
                .name("予約管理")
                .slug("reservation")
                .description("予約管理モジュール")
                .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                .moduleNumber(1)
                .requiresPaidPlan(false)
                .isActive(true)
                .build();
    }

    // ========================================
    // createTemplate
    // ========================================

    @Nested
    @DisplayName("createTemplate")
    class CreateTemplate {

        @Test
        @DisplayName("作成_モジュールIDなし_テンプレートのみ作成")
        void 作成_モジュールIDなし_テンプレートのみ作成() {
            // Given
            CreateTemplateRequest request = new CreateTemplateRequest(
                    "新テンプレート", "new-template", "説明文", "general", null);
            given(teamTemplateRepository.save(any(TeamTemplateEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(templateModuleRepository.findByTemplateId(any())).willReturn(List.of());

            // When
            ApiResponse<TemplateResponse> response = systemAdminTemplateService.createTemplate(request, USER_ID);

            // Then
            TemplateResponse data = response.getData();
            assertThat(data.getName()).isEqualTo("新テンプレート");
            assertThat(data.getSlug()).isEqualTo("new-template");
            assertThat(data.getIsActive()).isTrue();
            assertThat(data.getModules()).isEmpty();
            verify(teamTemplateRepository).save(any(TeamTemplateEntity.class));
        }

        @Test
        @DisplayName("作成_モジュールIDあり_テンプレートとモジュール紐付け作成")
        void 作成_モジュールIDあり_テンプレートとモジュール紐付け作成() {
            // Given
            CreateTemplateRequest request = new CreateTemplateRequest(
                    "新テンプレート", "new-template", "説明文", "sports", List.of(MODULE_ID));
            given(teamTemplateRepository.save(any(TeamTemplateEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(templateModuleRepository.save(any(TemplateModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            TemplateModuleEntity tm = TemplateModuleEntity.builder()
                    .templateId(null)
                    .moduleId(MODULE_ID)
                    .build();
            given(templateModuleRepository.findByTemplateId(any())).willReturn(List.of(tm));

            ModuleDefinitionEntity module = createModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));

            // When
            ApiResponse<TemplateResponse> response = systemAdminTemplateService.createTemplate(request, USER_ID);

            // Then
            TemplateResponse data = response.getData();
            assertThat(data.getName()).isEqualTo("新テンプレート");
            assertThat(data.getModules()).hasSize(1);
            assertThat(data.getModules().get(0).getName()).isEqualTo("予約管理");
            verify(templateModuleRepository).save(any(TemplateModuleEntity.class));
        }
    }

    // ========================================
    // updateTemplate
    // ========================================

    @Nested
    @DisplayName("updateTemplate")
    class UpdateTemplate {

        @Test
        @DisplayName("更新_全フィールド指定_全フィールド更新")
        void 更新_全フィールド指定_全フィールド更新() {
            // Given
            TeamTemplateEntity template = createTemplate();
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));
            given(teamTemplateRepository.save(any(TeamTemplateEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // findByTemplateId is called twice: 1st for delete existing, 2nd for getModuleSummaries
            TemplateModuleEntity tm = TemplateModuleEntity.builder()
                    .templateId(TEMPLATE_ID).moduleId(MODULE_ID).build();
            given(templateModuleRepository.findByTemplateId(TEMPLATE_ID))
                    .willReturn(List.of())       // 1st call: no existing modules
                    .willReturn(List.of(tm));     // 2nd call: after new module saved

            UpdateTemplateRequest request = new UpdateTemplateRequest(
                    "更新後名称", "更新後説明", "https://example.com/new-icon.png",
                    "education", false, List.of(MODULE_ID));
            given(templateModuleRepository.save(any(TemplateModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            ModuleDefinitionEntity module = createModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));

            // When
            ApiResponse<TemplateResponse> response = systemAdminTemplateService.updateTemplate(TEMPLATE_ID, request);

            // Then
            TemplateResponse data = response.getData();
            assertThat(data.getName()).isEqualTo("更新後名称");
            assertThat(data.getDescription()).isEqualTo("更新後説明");
            assertThat(data.getCategory()).isEqualTo("education");
            assertThat(data.getIsActive()).isFalse();
            verify(teamTemplateRepository).save(any(TeamTemplateEntity.class));
            verify(templateModuleRepository).deleteAll(any());
        }

        @Test
        @DisplayName("更新_一部フィールドnull_既存値を保持")
        void 更新_一部フィールドnull_既存値を保持() {
            // Given
            TeamTemplateEntity template = createTemplate();
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));
            given(teamTemplateRepository.save(any(TeamTemplateEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(templateModuleRepository.findByTemplateId(TEMPLATE_ID)).willReturn(List.of());

            UpdateTemplateRequest request = new UpdateTemplateRequest(
                    null, null, null, null, null, null);

            // When
            ApiResponse<TemplateResponse> response = systemAdminTemplateService.updateTemplate(TEMPLATE_ID, request);

            // Then
            TemplateResponse data = response.getData();
            assertThat(data.getName()).isEqualTo("スポーツチーム");
            assertThat(data.getDescription()).isEqualTo("スポーツチーム向けテンプレート");
            assertThat(data.getCategory()).isEqualTo("sports");
            assertThat(data.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("更新_存在しないID_TMPL001例外")
        void 更新_存在しないID_TMPL001例外() {
            // Given
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());
            UpdateTemplateRequest request = new UpdateTemplateRequest(
                    "更新", null, null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> systemAdminTemplateService.updateTemplate(TEMPLATE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_001"));
        }
    }

    // ========================================
    // deleteTemplate
    // ========================================

    @Nested
    @DisplayName("deleteTemplate")
    class DeleteTemplate {

        @Test
        @DisplayName("削除_存在するID_論理削除実行")
        void 削除_存在するID_論理削除実行() {
            // Given
            TeamTemplateEntity template = createTemplate();
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));

            // When
            systemAdminTemplateService.deleteTemplate(TEMPLATE_ID);

            // Then
            assertThat(template.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("削除_存在しないID_TMPL001例外")
        void 削除_存在しないID_TMPL001例外() {
            // Given
            given(teamTemplateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> systemAdminTemplateService.deleteTemplate(TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_001"));
        }
    }

    // ========================================
    // updateLevelAvailability
    // ========================================

    @Nested
    @DisplayName("updateLevelAvailability")
    class UpdateLevelAvailability {

        @Test
        @DisplayName("更新_正常_利用可否が更新される")
        void 更新_正常_利用可否が更新される() {
            // Given
            ModuleDefinitionEntity module = createModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));

            ModuleLevelAvailabilityEntity availability = ModuleLevelAvailabilityEntity.builder()
                    .moduleId(MODULE_ID)
                    .level(ModuleLevelAvailabilityEntity.Level.TEAM)
                    .isAvailable(false)
                    .build();
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    MODULE_ID, ModuleLevelAvailabilityEntity.Level.TEAM))
                    .willReturn(Optional.of(availability));
            given(moduleLevelAvailabilityRepository.save(any(ModuleLevelAvailabilityEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            UpdateLevelAvailabilityRequest request = new UpdateLevelAvailabilityRequest("TEAM", true);

            // When
            systemAdminTemplateService.updateLevelAvailability(MODULE_ID, request);

            // Then
            verify(moduleLevelAvailabilityRepository).save(any(ModuleLevelAvailabilityEntity.class));
        }

        @Test
        @DisplayName("更新_モジュール不在_TMPL002例外")
        void 更新_モジュール不在_TMPL002例外() {
            // Given
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.empty());
            UpdateLevelAvailabilityRequest request = new UpdateLevelAvailabilityRequest("TEAM", true);

            // When / Then
            assertThatThrownBy(() -> systemAdminTemplateService.updateLevelAvailability(MODULE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_002"));
        }

        @Test
        @DisplayName("更新_レベル別設定不在_TMPL002例外")
        void 更新_レベル別設定不在_TMPL002例外() {
            // Given
            ModuleDefinitionEntity module = createModule();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    MODULE_ID, ModuleLevelAvailabilityEntity.Level.ORGANIZATION))
                    .willReturn(Optional.empty());

            UpdateLevelAvailabilityRequest request = new UpdateLevelAvailabilityRequest("ORGANIZATION", true);

            // When / Then
            assertThatThrownBy(() -> systemAdminTemplateService.updateLevelAvailability(MODULE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_002"));
        }
    }
}
