package com.mannschaft.app.forms;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.forms.dto.CreateFormTemplateRequest;
import com.mannschaft.app.forms.dto.FormFieldRequest;
import com.mannschaft.app.forms.dto.FormTemplateResponse;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.entity.FormTemplateFieldEntity;
import com.mannschaft.app.forms.repository.FormTemplateFieldRepository;
import com.mannschaft.app.forms.repository.FormTemplateRepository;
import com.mannschaft.app.forms.service.FormTemplateService;
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
 * {@link FormTemplateService} の単体テスト。
 * テンプレートのCRUD・ステータス遷移を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FormTemplateService 単体テスト")
class FormTemplateServiceTest {

    @Mock
    private FormTemplateRepository templateRepository;

    @Mock
    private FormTemplateFieldRepository fieldRepository;

    @Mock
    private FormMapper formMapper;

    @InjectMocks
    private FormTemplateService formTemplateService;

    private static final Long TEMPLATE_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final String SCOPE_TYPE = "TEAM";

    private FormTemplateEntity createDraftTemplate() {
        return FormTemplateEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                .name("休暇届").createdBy(USER_ID).build();
    }

    private FormTemplateEntity createPublishedTemplate() {
        FormTemplateEntity entity = createDraftTemplate();
        entity.publish();
        return entity;
    }

    @Nested
    @DisplayName("publishTemplate")
    class PublishTemplate {

        @Test
        @DisplayName("テンプレート公開_正常_PUBLISHED状態に遷移")
        void テンプレート公開_正常_PUBLISHED状態に遷移() {
            // Given
            FormTemplateEntity entity = createDraftTemplate();
            FormTemplateResponse response = new FormTemplateResponse(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID,
                    "休暇届", null, null, null, "PUBLISHED", false, null, false,
                    null, false, false, 0, 0, null, 0, 0, USER_ID, null, null, null, null, null, List.of());

            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(fieldRepository.countByTemplateId(TEMPLATE_ID)).willReturn(3L);
            given(templateRepository.save(entity)).willReturn(entity);
            given(fieldRepository.findByTemplateIdOrderBySortOrderAsc(TEMPLATE_ID)).willReturn(List.of());
            given(formMapper.toTemplateResponseWithFields(entity, List.of())).willReturn(response);

            // When
            FormTemplateResponse result = formTemplateService.publishTemplate(SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(FormStatus.PUBLISHED);
        }

        @Test
        @DisplayName("テンプレート公開_PUBLISHED状態_BusinessException")
        void テンプレート公開_PUBLISHED状態_BusinessException() {
            // Given
            FormTemplateEntity entity = createPublishedTemplate();

            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> formTemplateService.publishTemplate(SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FormErrorCode.INVALID_TEMPLATE_STATUS));
        }

        @Test
        @DisplayName("テンプレート公開_フィールドなし_BusinessException")
        void テンプレート公開_フィールドなし_BusinessException() {
            // Given
            FormTemplateEntity entity = createDraftTemplate();

            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(fieldRepository.countByTemplateId(TEMPLATE_ID)).willReturn(0L);

            // When & Then
            assertThatThrownBy(() -> formTemplateService.publishTemplate(SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FormErrorCode.EMPTY_FIELDS));
        }
    }

    @Nested
    @DisplayName("closeTemplate")
    class CloseTemplate {

        @Test
        @DisplayName("テンプレート閉鎖_正常_CLOSED状態に遷移")
        void テンプレート閉鎖_正常_CLOSED状態に遷移() {
            // Given
            FormTemplateEntity entity = createPublishedTemplate();
            FormTemplateResponse response = new FormTemplateResponse(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID,
                    "休暇届", null, null, null, "CLOSED", false, null, false,
                    null, false, false, 0, 0, null, 0, 0, USER_ID, null, null, null, null, null, List.of());

            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(templateRepository.save(entity)).willReturn(entity);
            given(fieldRepository.findByTemplateIdOrderBySortOrderAsc(TEMPLATE_ID)).willReturn(List.of());
            given(formMapper.toTemplateResponseWithFields(entity, List.of())).willReturn(response);

            // When
            formTemplateService.closeTemplate(SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(FormStatus.CLOSED);
        }

        @Test
        @DisplayName("テンプレート閉鎖_DRAFT状態_BusinessException")
        void テンプレート閉鎖_DRAFT状態_BusinessException() {
            // Given
            FormTemplateEntity entity = createDraftTemplate();

            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> formTemplateService.closeTemplate(SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FormErrorCode.INVALID_TEMPLATE_STATUS));
        }
    }

    @Nested
    @DisplayName("deleteTemplate")
    class DeleteTemplate {

        @Test
        @DisplayName("テンプレート削除_正常_論理削除実行")
        void テンプレート削除_正常_論理削除実行() {
            // Given
            FormTemplateEntity entity = createDraftTemplate();
            given(templateRepository.findByIdAndScopeTypeAndScopeId(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            formTemplateService.deleteTemplate(SCOPE_TYPE, SCOPE_ID, TEMPLATE_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(templateRepository).save(entity);
        }
    }

    @Nested
    @DisplayName("createTemplate - validateFieldKeys")
    class ValidateFieldKeys {

        @Test
        @DisplayName("テンプレート作成_フィールドキー重複_BusinessException")
        void テンプレート作成_フィールドキー重複_BusinessException() {
            // Given
            FormFieldRequest field1 = new FormFieldRequest(
                    "name", "名前", "TEXT", null, null, null, null, null);

            FormFieldRequest field2 = new FormFieldRequest(
                    "name", "名前2", "TEXT", null, null, null, null, null);

            CreateFormTemplateRequest request = new CreateFormTemplateRequest(
                    "テスト", null, null, null, null, null, null, null,
                    null, null, null, null, null, null, List.of(field1, field2));

            given(templateRepository.save(any(FormTemplateEntity.class)))
                    .willReturn(createDraftTemplate());

            // When & Then
            assertThatThrownBy(() -> formTemplateService.createTemplate(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FormErrorCode.DUPLICATE_FIELD_KEY));
        }
    }
}
