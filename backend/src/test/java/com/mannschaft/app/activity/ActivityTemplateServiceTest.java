package com.mannschaft.app.activity;

import com.mannschaft.app.activity.dto.ActivityTemplateResponse;
import com.mannschaft.app.activity.dto.CreateTemplateRequest;
import com.mannschaft.app.activity.dto.DuplicateTemplateRequest;
import com.mannschaft.app.activity.dto.ImportTemplateRequest;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateRepository;
import com.mannschaft.app.activity.repository.SystemActivityTemplatePresetRepository;
import com.mannschaft.app.activity.service.ActivityTemplateService;
import com.mannschaft.app.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityTemplateService 単体テスト")
class ActivityTemplateServiceTest {

    @Mock private ActivityTemplateRepository templateRepository;
    @Mock private ActivityTemplateFieldRepository fieldRepository;
    @Mock private SystemActivityTemplatePresetRepository presetRepository;
    @Mock private ActivityMapper activityMapper;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private ActivityTemplateService service;

    private static final Long TEMPLATE_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 1L;

    private ActivityTemplateEntity createTemplate() {
        return ActivityTemplateEntity.builder()
                .scopeType(ActivityScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .name("テストテンプレート")
                .defaultVisibility(ActivityVisibility.MEMBERS_ONLY)
                .isParticipantRequired(true)
                .createdBy(USER_ID)
                .build();
    }

    @Nested
    @DisplayName("createTemplate")
    class CreateTemplate {

        @Test
        @DisplayName("正常系: テンプレートが作成される")
        void 作成_正常_保存() {
            given(templateRepository.countByScopeTypeAndScopeId(ActivityScopeType.TEAM, SCOPE_ID)).willReturn(0L);
            ActivityTemplateEntity saved = createTemplate();
            given(templateRepository.save(any())).willReturn(saved);
            given(fieldRepository.findByTemplateIdOrderBySortOrderAsc(any())).willReturn(List.of());
            given(activityMapper.toTemplateFieldResponseList(any())).willReturn(List.of());

            CreateTemplateRequest request = new CreateTemplateRequest(
                    "新テンプレート", null, null, null, null, null, null);
            ActivityTemplateResponse result = service.createTemplate(USER_ID, ActivityScopeType.TEAM, SCOPE_ID, request);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: テンプレート上限超過でACTIVITY_005例外")
        void 作成_上限超過_例外() {
            given(templateRepository.countByScopeTypeAndScopeId(ActivityScopeType.TEAM, SCOPE_ID)).willReturn(20L);
            CreateTemplateRequest request = new CreateTemplateRequest(
                    "21番目", null, null, null, null, null, null);

            assertThatThrownBy(() -> service.createTemplate(USER_ID, ActivityScopeType.TEAM, SCOPE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_005"));
        }

        @Test
        @DisplayName("異常系: フィールド上限超過でACTIVITY_006例外")
        void 作成_フィールド上限超過_例外() {
            given(templateRepository.countByScopeTypeAndScopeId(ActivityScopeType.TEAM, SCOPE_ID)).willReturn(0L);
            List<CreateTemplateRequest.TemplateFieldInput> fields = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) {
                CreateTemplateRequest.TemplateFieldInput fi = new CreateTemplateRequest.TemplateFieldInput(
                        "key" + i, "label" + i, "TEXT", null, null, null, null, null, null);
                fields.add(fi);
            }
            CreateTemplateRequest request = new CreateTemplateRequest(
                    "テンプレート", null, null, null, null, null, fields);

            assertThatThrownBy(() -> service.createTemplate(USER_ID, ActivityScopeType.TEAM, SCOPE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_006"));
        }
    }

    @Nested
    @DisplayName("deleteTemplate")
    class DeleteTemplate {
        @Test
        @DisplayName("正常系: テンプレートが論理削除される")
        void 削除_正常_論理削除() {
            ActivityTemplateEntity entity = createTemplate();
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(entity));
            service.deleteTemplate(TEMPLATE_ID);
            verify(templateRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: テンプレート不在でACTIVITY_002例外")
        void 削除_不在_例外() {
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteTemplate(TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_002"));
        }
    }

    @Nested
    @DisplayName("duplicateTemplate")
    class DuplicateTemplate {
        @Test
        @DisplayName("異常系: コピー先の上限超過でACTIVITY_005例外")
        void 複製_上限超過_例外() {
            ActivityTemplateEntity source = createTemplate();
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(source));
            given(templateRepository.countByScopeTypeAndScopeId(ActivityScopeType.ORGANIZATION, 2L)).willReturn(20L);

            DuplicateTemplateRequest request = new DuplicateTemplateRequest("ORGANIZATION", 2L);

            assertThatThrownBy(() -> service.duplicateTemplate(TEMPLATE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_005"));
        }
    }

    @Nested
    @DisplayName("importPreset")
    class ImportPreset {
        @Test
        @DisplayName("異常系: プリセット不在でACTIVITY_016例外")
        void インポート_プリセット不在_例外() {
            given(presetRepository.findById(99L)).willReturn(Optional.empty());
            ImportTemplateRequest request = new ImportTemplateRequest(99L, "TEAM", SCOPE_ID);

            assertThatThrownBy(() -> service.importPreset(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_016"));
        }
    }
}
