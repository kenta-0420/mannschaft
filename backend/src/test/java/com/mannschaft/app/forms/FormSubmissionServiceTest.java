package com.mannschaft.app.forms;

import com.mannschaft.app.common.AccessControlService;
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

    @Mock
    private com.mannschaft.app.common.storage.StorageService storageService;

    @Mock
    private AccessControlService accessControlService;

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
            FormSubmissionResponse response = FormSubmissionResponse.builder()
                    .id(SUBMISSION_ID).status("APPROVED")
                    .scope(new FormSubmissionResponse.FormScopeDto(SCOPE_TYPE, SCOPE_ID))
                    .meta(new FormSubmissionResponse.FormSubmissionMetaDto(TEMPLATE_ID, USER_ID, null, 1, null))
                    .pdf(new FormSubmissionResponse.FormSubmissionPdfDto(null))
                    .audit(new FormSubmissionResponse.FormSubmissionAuditDto(null, null))
                    .values(List.of())
                    .build();

            given(submissionRepository.findById(SUBMISSION_ID)).willReturn(Optional.of(entity));
            given(submissionRepository.save(entity)).willReturn(entity);
            given(valueRepository.findBySubmissionId(SUBMISSION_ID)).willReturn(List.of());
            given(formMapper.toSubmissionResponseWithValues(entity, List.of())).willReturn(response);

            // When
            formSubmissionService.approveSubmission(SCOPE_TYPE, SCOPE_ID, USER_ID, TEMPLATE_ID, SUBMISSION_ID);

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
            assertThatThrownBy(() -> formSubmissionService.approveSubmission(SCOPE_TYPE, SCOPE_ID, USER_ID, TEMPLATE_ID, SUBMISSION_ID))
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
            FormSubmissionResponse response = FormSubmissionResponse.builder()
                    .id(SUBMISSION_ID).status("REJECTED")
                    .scope(new FormSubmissionResponse.FormScopeDto(SCOPE_TYPE, SCOPE_ID))
                    .meta(new FormSubmissionResponse.FormSubmissionMetaDto(TEMPLATE_ID, USER_ID, null, 1, null))
                    .pdf(new FormSubmissionResponse.FormSubmissionPdfDto(null))
                    .audit(new FormSubmissionResponse.FormSubmissionAuditDto(null, null))
                    .values(List.of())
                    .build();

            given(submissionRepository.findById(SUBMISSION_ID)).willReturn(Optional.of(entity));
            given(submissionRepository.save(entity)).willReturn(entity);
            given(valueRepository.findBySubmissionId(SUBMISSION_ID)).willReturn(List.of());
            given(formMapper.toSubmissionResponseWithValues(entity, List.of())).willReturn(response);

            // When
            formSubmissionService.rejectSubmission(SCOPE_TYPE, SCOPE_ID, USER_ID, TEMPLATE_ID, SUBMISSION_ID);

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
            FormSubmissionResponse response = FormSubmissionResponse.builder()
                    .id(SUBMISSION_ID).status("RETURNED")
                    .scope(new FormSubmissionResponse.FormScopeDto(SCOPE_TYPE, SCOPE_ID))
                    .meta(new FormSubmissionResponse.FormSubmissionMetaDto(TEMPLATE_ID, USER_ID, null, 1, null))
                    .pdf(new FormSubmissionResponse.FormSubmissionPdfDto(null))
                    .audit(new FormSubmissionResponse.FormSubmissionAuditDto(null, null))
                    .values(List.of())
                    .build();

            given(submissionRepository.findById(SUBMISSION_ID)).willReturn(Optional.of(entity));
            given(submissionRepository.save(entity)).willReturn(entity);
            given(valueRepository.findBySubmissionId(SUBMISSION_ID)).willReturn(List.of());
            given(formMapper.toSubmissionResponseWithValues(entity, List.of())).willReturn(response);

            // When
            formSubmissionService.returnSubmission(SCOPE_TYPE, SCOPE_ID, USER_ID, TEMPLATE_ID, SUBMISSION_ID);

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

    /**
     * F05.7 Phase 11 第一陣 A 分類: 外部 API 用 submit のテスト。
     */
    @Nested
    @DisplayName("submit (Phase 11 外部 API)")
    class Submit {

        @Test
        @DisplayName("提出実行_DRAFT_SUBMITTED遷移成功")
        void 提出実行_DRAFT_SUBMITTED遷移成功() {
            // Given
            FormSubmissionEntity entity = createDraftSubmission();
            FormTemplateEntity template = createPublishedTemplate();
            given(submissionRepository.findByIdAndSubmittedBy(SUBMISSION_ID, USER_ID))
                    .willReturn(Optional.of(entity));
            given(templateService.getTemplateEntity(TEMPLATE_ID)).willReturn(template);
            given(submissionRepository.save(entity)).willReturn(entity);
            given(valueRepository.findBySubmissionId(SUBMISSION_ID)).willReturn(List.of());
            FormSubmissionResponse stub = FormSubmissionResponse.builder()
                    .id(SUBMISSION_ID).status("SUBMITTED")
                    .scope(new FormSubmissionResponse.FormScopeDto(SCOPE_TYPE, SCOPE_ID))
                    .meta(new FormSubmissionResponse.FormSubmissionMetaDto(TEMPLATE_ID, USER_ID, null, 1, 0L))
                    .pdf(new FormSubmissionResponse.FormSubmissionPdfDto(null))
                    .audit(new FormSubmissionResponse.FormSubmissionAuditDto(null, null))
                    .values(List.of())
                    .build();
            given(formMapper.toSubmissionResponseWithValues(entity, List.of()))
                    .willReturn(stub);

            // When
            FormSubmissionResponse response = formSubmissionService.submit(SUBMISSION_ID, USER_ID);

            // Then
            assertThat(response).isNotNull();
            assertThat(entity.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
            assertThat(template.getSubmissionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("提出実行_提出未存在_FORM_002")
        void 提出実行_提出未存在_FORM_002() {
            // Given
            given(submissionRepository.findByIdAndSubmittedBy(SUBMISSION_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> formSubmissionService.submit(SUBMISSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FormErrorCode.SUBMISSION_NOT_FOUND));
        }

        @Test
        @DisplayName("提出実行_既にSUBMITTED_FORM_005")
        void 提出実行_既にSUBMITTED_FORM_005() {
            // Given
            FormSubmissionEntity entity = createSubmittedSubmission();
            given(submissionRepository.findByIdAndSubmittedBy(SUBMISSION_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> formSubmissionService.submit(SUBMISSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FormErrorCode.INVALID_SUBMISSION_STATUS));
        }

        @Test
        @DisplayName("提出実行_テンプレート未公開_FORM_006")
        void 提出実行_テンプレート未公開_FORM_006() {
            // Given
            FormSubmissionEntity entity = createDraftSubmission();
            FormTemplateEntity template = FormTemplateEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).name("休暇届").createdBy(1L).build();
            // publish() 呼ばず DRAFT のまま
            given(submissionRepository.findByIdAndSubmittedBy(SUBMISSION_ID, USER_ID))
                    .willReturn(Optional.of(entity));
            given(templateService.getTemplateEntity(TEMPLATE_ID)).willReturn(template);

            // When & Then
            assertThatThrownBy(() -> formSubmissionService.submit(SUBMISSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FormErrorCode.TEMPLATE_NOT_PUBLISHED));
        }
    }

    @Nested
    @DisplayName("createSubmissionForRequirement（F08.7.1/06 大会提出枠連結）")
    class CreateSubmissionForRequirement {

        private final java.util.UUID REQUIREMENT_ID = java.util.UUID.randomUUID();

        @Test
        @DisplayName("新規提出は form_submission に requirement_id(UUID) を設定して保存する（§2.1 型整合）")
        void linksRequirementUuidOnNewSubmission() {
            FormTemplateEntity template = createPublishedTemplate();
            given(templateService.getTemplateEntity(TEMPLATE_ID)).willReturn(template);
            given(submissionRepository.findByTournamentSubmissionRequirementIdAndScopeTypeAndScopeId(
                    REQUIREMENT_ID, SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.empty());
            given(submissionRepository.countByTemplateIdAndSubmittedBy(TEMPLATE_ID, USER_ID)).willReturn(0L);
            given(submissionRepository.save(org.mockito.ArgumentMatchers.any(FormSubmissionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(formMapper.toSubmissionResponseWithValues(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .willReturn(FormSubmissionResponse.builder().status("SUBMITTED").build());

            CreateFormSubmissionRequest request = new CreateFormSubmissionRequest(TEMPLATE_ID, true, null);

            formSubmissionService.createSubmissionForRequirement(
                    SCOPE_TYPE, SCOPE_ID, USER_ID, REQUIREMENT_ID, request);

            org.mockito.ArgumentCaptor<FormSubmissionEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(FormSubmissionEntity.class);
            verify(submissionRepository).save(captor.capture());
            // BIGINT 連結（workflowRequestId）と UUID 連結（requirementId）が別カラムで保持されること
            assertThat(captor.getValue().getTournamentSubmissionRequirementId()).isEqualTo(REQUIREMENT_ID);
            assertThat(captor.getValue().getScopeType()).isEqualTo(SCOPE_TYPE);
            assertThat(captor.getValue().getScopeId()).isEqualTo(SCOPE_ID);
            assertThat(captor.getValue().getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
        }

        @Test
        @DisplayName("差戻し（RETURNED）の既存提出があれば新規作成せず上書き再提出する")
        void overwritesEditableExistingSubmission() {
            FormTemplateEntity template = createPublishedTemplate();
            FormSubmissionEntity existing = FormSubmissionEntity.builder()
                    .templateId(TEMPLATE_ID).scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                    .tournamentSubmissionRequirementId(REQUIREMENT_ID)
                    .status(SubmissionStatus.RETURNED).submittedBy(USER_ID).build();
            given(templateService.getTemplateEntity(TEMPLATE_ID)).willReturn(template);
            given(submissionRepository.findByTournamentSubmissionRequirementIdAndScopeTypeAndScopeId(
                    REQUIREMENT_ID, SCOPE_TYPE, SCOPE_ID)).willReturn(Optional.of(existing));
            given(submissionRepository.save(org.mockito.ArgumentMatchers.any(FormSubmissionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(formMapper.toSubmissionResponseWithValues(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .willReturn(FormSubmissionResponse.builder().status("SUBMITTED").build());

            CreateFormSubmissionRequest request = new CreateFormSubmissionRequest(TEMPLATE_ID, true, null);

            formSubmissionService.createSubmissionForRequirement(
                    SCOPE_TYPE, SCOPE_ID, USER_ID, REQUIREMENT_ID, request);

            // 既存提出が SUBMITTED に遷移し、件数カウントの新規採番（countByTemplateIdAndSubmittedBy）は呼ばれない
            assertThat(existing.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
            verify(submissionRepository).save(existing);
            verify(submissionRepository, org.mockito.Mockito.never())
                    .countByTemplateIdAndSubmittedBy(org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyLong());
        }
    }
}
