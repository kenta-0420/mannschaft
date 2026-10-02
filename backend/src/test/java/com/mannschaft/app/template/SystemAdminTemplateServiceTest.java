package com.mannschaft.app.template;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.template.dto.CreateTemplateRequest;
import com.mannschaft.app.template.dto.TemplateResponse;
import com.mannschaft.app.template.dto.UpdateLevelAvailabilityRequest;
import com.mannschaft.app.template.dto.UpdateModuleActiveRequest;
import com.mannschaft.app.template.dto.UpdateModulePaidPlanRequest;
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

import org.mockito.ArgumentCaptor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;

import java.lang.reflect.Method;
import java.util.Arrays;

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

            // Then: toBuilder()→id=null→INSERT化バグの回帰防止。
            // save に渡るのが findById の同一インスタンス（管理対象）であることを確認。
            ArgumentCaptor<TeamTemplateEntity> captor = ArgumentCaptor.forClass(TeamTemplateEntity.class);
            verify(teamTemplateRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(template);

            TemplateResponse data = response.getData();
            assertThat(data.getName()).isEqualTo("更新後名称");
            assertThat(data.getDescription()).isEqualTo("更新後説明");
            assertThat(data.getCategory()).isEqualTo("education");
            assertThat(data.getIsActive()).isFalse();
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

            // Then: toBuilder()→id=null→INSERT化バグの回帰防止。
            // save に渡るのが findById の同一インスタンス（管理対象）であることを確認。
            ArgumentCaptor<ModuleLevelAvailabilityEntity> captor =
                    ArgumentCaptor.forClass(ModuleLevelAvailabilityEntity.class);
            verify(moduleLevelAvailabilityRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(availability);
            assertThat(captor.getValue().getIsAvailable()).isTrue();
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

    // ========================================
    // updateModulePaidPlan（AC-1・AC-3・AC-4）
    // ========================================

    @Nested
    @DisplayName("updateModulePaidPlan")
    class UpdateModulePaidPlan {

        @Test
        @DisplayName("AC-1: 更新_有料要否をtrueへ反転_同一インスタンスで保存")
        void 更新_有料要否をtrueへ反転() {
            // Given: requiresPaidPlan=false のモジュール
            ModuleDefinitionEntity module = createModule();
            assertThat(module.getRequiresPaidPlan()).isFalse();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleDefinitionRepository.save(any(ModuleDefinitionEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            UpdateModulePaidPlanRequest request = new UpdateModulePaidPlanRequest(true);

            // When
            systemAdminTemplateService.updateModulePaidPlan(MODULE_ID, request);

            // Then: toBuilder()→id=null→INSERT化バグの回帰防止。findById の同一インスタンスを保存。
            ArgumentCaptor<ModuleDefinitionEntity> captor =
                    ArgumentCaptor.forClass(ModuleDefinitionEntity.class);
            verify(moduleDefinitionRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(module);
            assertThat(captor.getValue().getRequiresPaidPlan()).isTrue();
        }

        @Test
        @DisplayName("AC-3: 更新_モジュール不在_TMPL002例外")
        void 更新_モジュール不在_TMPL002例外() {
            // Given
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.empty());
            UpdateModulePaidPlanRequest request = new UpdateModulePaidPlanRequest(true);

            // When / Then
            assertThatThrownBy(() -> systemAdminTemplateService.updateModulePaidPlan(MODULE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_002"));
        }

        @Test
        @DisplayName("AC-4: moduleCatalog(allEntries) と moduleDetail(key=#moduleId) を evict する")
        void キャッシュevict注釈が付与されている() throws Exception {
            Method method = SystemAdminTemplateService.class.getMethod(
                    "updateModulePaidPlan", Long.class, UpdateModulePaidPlanRequest.class);
            assertCacheEvicts(method);
        }
    }

    // ========================================
    // updateModuleActive（AC-2・AC-3・AC-4）
    // ========================================

    @Nested
    @DisplayName("updateModuleActive")
    class UpdateModuleActive {

        @Test
        @DisplayName("AC-2: 更新_有効状態をfalseへ反転_同一インスタンスで保存")
        void 更新_有効状態をfalseへ反転() {
            // Given: isActive=true のモジュール
            ModuleDefinitionEntity module = createModule();
            assertThat(module.getIsActive()).isTrue();
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.of(module));
            given(moduleDefinitionRepository.save(any(ModuleDefinitionEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            UpdateModuleActiveRequest request = new UpdateModuleActiveRequest(false);

            // When
            systemAdminTemplateService.updateModuleActive(MODULE_ID, request);

            // Then
            ArgumentCaptor<ModuleDefinitionEntity> captor =
                    ArgumentCaptor.forClass(ModuleDefinitionEntity.class);
            verify(moduleDefinitionRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(module);
            assertThat(captor.getValue().getIsActive()).isFalse();
        }

        @Test
        @DisplayName("AC-3: 更新_モジュール不在_TMPL002例外")
        void 更新_モジュール不在_TMPL002例外() {
            // Given
            given(moduleDefinitionRepository.findById(MODULE_ID)).willReturn(Optional.empty());
            UpdateModuleActiveRequest request = new UpdateModuleActiveRequest(false);

            // When / Then
            assertThatThrownBy(() -> systemAdminTemplateService.updateModuleActive(MODULE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TMPL_002"));
        }

        @Test
        @DisplayName("AC-4: moduleCatalog(allEntries) と moduleDetail(key=#moduleId) を evict する")
        void キャッシュevict注釈が付与されている() throws Exception {
            Method method = SystemAdminTemplateService.class.getMethod(
                    "updateModuleActive", Long.class, UpdateModuleActiveRequest.class);
            assertCacheEvicts(method);
        }
    }

    /**
     * AC-4: 対象メソッドが {@code updateLevelAvailability} と同じキャッシュ evict 契約
     * （{@code moduleCatalog} allEntries + {@code moduleDetail} key=#moduleId）を持つことを検証する。
     * テストプロファイルは {@code cache.type=none} のため振る舞いでは検証できず、注釈の存在で担保する。
     */
    private void assertCacheEvicts(Method method) {
        Caching caching = method.getAnnotation(Caching.class);
        assertThat(caching).as("@Caching が付与されていること").isNotNull();

        CacheEvict[] evicts = caching.evict();
        boolean catalogEvict = Arrays.stream(evicts)
                .anyMatch(e -> Arrays.asList(e.value()).contains("moduleCatalog") && e.allEntries());
        boolean detailEvict = Arrays.stream(evicts)
                .anyMatch(e -> Arrays.asList(e.value()).contains("moduleDetail")
                        && "#moduleId".equals(e.key()));

        assertThat(catalogEvict).as("moduleCatalog を allEntries で evict すること").isTrue();
        assertThat(detailEvict).as("moduleDetail を key=#moduleId で evict すること").isTrue();
    }
}
