package com.mannschaft.app.forms;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.forms.dto.CreateFormSubmissionRequest;
import com.mannschaft.app.forms.dto.FormSubmissionResponse;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.repository.FormSubmissionRepository;
import com.mannschaft.app.forms.repository.FormSubmissionValueRepository;
import com.mannschaft.app.forms.service.FormSubmissionService;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link FormSubmissionService} の単体テスト。
 * 提出のCRUD・ステータス遷移を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FormSubmissionService 単体テスト")
class FormSubmissionServiceTest {

    @Mock
    private FormSubmissionRepository submissionRepository;

    @Mock
    private FormSubmissionValueRepository valueRepository;

    @Mock
    private FormTemplateService templateService;

    @Mock
    private FormMapper formMapper;

    @InjectMocks
    private FormSubmissionService formSubmissionService;

    private static final Long SUBMISSION_ID = 200L;
    private static final Long TEMPLATE_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;

    private FormTemplateEntity createPublishedTemplate() {
        FormTemplateEntity entity = FormTemplateEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).name("休暇届").createdBy(1L).build();
        entity.publish();
        return entity;
    }

    private FormSubmissionEntity createDraftSubmission() {
        return FormSubmissionEntity.builder()
                .templateId(TEMPLATE_ID).scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                .submittedBy(USER_ID).build();
    }

    private FormSubmissionEntity createSubmittedSubmission() {
        FormSubmissionEntity entity = createDraftSubmission();
        entity.submit();
        return entity;
    }

    @Nested
    @DisplayName("createSubmission")
    class CreateSubmission {

        @Test
        @DisplayName("提出作成_テンプレート未公開_BusinessException")
        void 提出作成_テンプレート未公開_BusinessException() {
            // Given
            FormTemplateEntity template = FormTemplateEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).name("テスト").createdBy(1L).build();

            CreateFormSubmissionRequest request = new CreateFormSubmissionRequest(
                    TEMPLATE_ID, null, null);

            given(templateService.getTemplateEntity(TEMPLATE_ID)).willReturn(template);

            // When & Then
            assertThatThrownBy(() -> formSubmissionService.createSubmission(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FormErrorCode.TEMPLATE_NOT_PUBLISHED));
        }

        @Test
        @DisplayName("提出作成_提出回数上限超過_BusinessException")
        void 提出作成_提出回数上限超過_BusinessException() {
            // Given
            FormTemplateEntity template = createPublishedTemplate();
            template = template.toBuilder().maxSubmissionsPerUser(1).build();

            CreateFormSubmissionRequest request = new CreateFormSubmissionRequest(
                    TEMPLATE_ID, null, null);

            given(templateService.getTemplateEntity(TEMPLATE_ID)).willReturn(template);
            given(submissionRepository.countByTemplateIdAndSubmittedBy(TEMPLATE_ID, USER_ID)).willReturn(1L);

            // When & Then
            assertThatThrownBy(() -> formSubmissionService.createSubmission(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FormErrorCode.MAX_SUBMISSIONS_EXCEEDED));
        }
    }

    @Nested
    @DisplayName("approveSubmission")
    class ApproveSubmission {

        @Test
        @DisplayName("提出承認_SUBMITTED状態_正常")
        void 提出承認_SUBMITTED状態_正常() {
            // Given
            FormSubmissionEntity entity = createSubmittedSubmission();
            FormSubmissionResponse response = new FormSubmissionResponse(SUBMISSION_ID, TEMPLATE_ID,
                    SCOPE_TYPE, SCOPE_ID, "APPROVED", USER_ID, null, null, 1, null, null, null, List.of());

            given(submissionRepository.findById(SUBMISSION_ID)).willReturn(Optional.of(entity));
            given(submissionRepository.save(entity)).willReturn(entity);
            given(valueRepository.findBySubmissionId(SUBMISSION_ID)).willReturn(List.of());
            given(formMapper.toSubmissionResponseWithValues(entity, List.of())).willReturn(response);

            // When
            formSubmissionService.approveSubmission(SUBMISSION_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
        }

        @Test
        @DisplayName("提出承認_DRAFT状態_BusinessException")
        void 提出承認_DRAFT状態_BusinessException() {
            // Given
            FormSubmissionEntity entity = createDraftSubmission();
            given(submissionRepository.findById(SUBMISSION_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> formSubmissionService.approveSubmission(SUBMISSION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FormErrorCode.INVALID_SUBMISSION_STATUS));
        }
    }

    @Nested
    @DisplayName("rejectSubmission")
    class RejectSubmission {

        @Test
        @DisplayName("提出却下_SUBMITTED状態_正常")
        void 提出却下_SUBMITTED状態_正常() {
            // Given
            FormSubmissionEntity entity = createSubmittedSubmission();
            FormSubmissionResponse response = new FormSubmissionResponse(SUBMISSION_ID, TEMPLATE_ID,
                    SCOPE_TYPE, SCOPE_ID, "REJECTED", USER_ID, null, null, 1, null, null, null, List.of());

            given(submissionRepository.findById(SUBMISSION_ID)).willReturn(Optional.of(entity));
            given(submissionRepository.save(entity)).willReturn(entity);
            given(valueRepository.findBySubmissionId(SUBMISSION_ID)).willReturn(List.of());
            given(formMapper.toSubmissionResponseWithValues(entity, List.of())).willReturn(response);

            // When
            formSubmissionService.rejectSubmission(SUBMISSION_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(SubmissionStatus.REJECTED);
        }
    }

    @Nested
    @DisplayName("returnSubmission")
    class ReturnSubmission {

        @Test
        @DisplayName("提出差し戻し_SUBMITTED状態_正常")
        void 提出差し戻し_SUBMITTED状態_正常() {
            // Given
            FormSubmissionEntity entity = createSubmittedSubmission();
            FormSubmissionResponse response = new FormSubmissionResponse(SUBMISSION_ID, TEMPLATE_ID,
                    SCOPE_TYPE, SCOPE_ID, "RETURNED", USER_ID, null, null, 1, null, null, null, List.of());

            given(submissionRepository.findById(SUBMISSION_ID)).willReturn(Optional.of(entity));
            given(submissionRepository.save(entity)).willReturn(entity);
            given(valueRepository.findBySubmissionId(SUBMISSION_ID)).willReturn(List.of());
            given(formMapper.toSubmissionResponseWithValues(entity, List.of())).willReturn(response);

            // When
            formSubmissionService.returnSubmission(SUBMISSION_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(SubmissionStatus.RETURNED);
        }
    }

    @Nested
    @DisplayName("deleteSubmission")
    class DeleteSubmission {

        @Test
        @DisplayName("提出削除_正常_論理削除実行")
        void 提出削除_正常_論理削除実行() {
            // Given
            FormSubmissionEntity entity = createDraftSubmission();
            given(submissionRepository.findByIdAndSubmittedBy(SUBMISSION_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            // When
            formSubmissionService.deleteSubmission(SUBMISSION_ID, USER_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(submissionRepository).save(entity);
        }

        @Test
        @DisplayName("提出削除_存在しない_BusinessException")
        void 提出削除_存在しない_BusinessException() {
            // Given
            given(submissionRepository.findByIdAndSubmittedBy(SUBMISSION_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> formSubmissionService.deleteSubmission(SUBMISSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FormErrorCode.SUBMISSION_NOT_FOUND));
        }
    }
}
