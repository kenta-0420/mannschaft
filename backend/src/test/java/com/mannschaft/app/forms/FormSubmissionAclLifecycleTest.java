package com.mannschaft.app.forms;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.common.storage.acl.StorageAclAttachmentBinding;
import com.mannschaft.app.common.storage.acl.StorageAclContentReference;
import com.mannschaft.app.common.storage.acl.StorageAclScope;
import com.mannschaft.app.common.storage.acl.StorageAclService;
import com.mannschaft.app.forms.dto.SubmissionValueRequest;
import com.mannschaft.app.forms.dto.UpdateFormSubmissionRequest;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import com.mannschaft.app.forms.entity.FormSubmissionValueEntity;
import com.mannschaft.app.forms.repository.FormSubmissionRepository;
import com.mannschaft.app.forms.repository.FormSubmissionValueRepository;
import com.mannschaft.app.forms.service.FormSubmissionService;
import com.mannschaft.app.forms.service.FormTemplateService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 全削除・再作成による添付 claim の破壊を防ぐ回帰テスト。 */
@ExtendWith(MockitoExtension.class)
class FormSubmissionAclLifecycleTest {
    @Mock private FormSubmissionRepository submissionRepository;
    @Mock private FormSubmissionValueRepository valueRepository;
    @Mock private FormTemplateService templateService;
    @Mock private FormMapper formMapper;
    @Mock private StorageService storageService;
    @Mock private StorageAclService storageAclService;
    @Mock private AccessControlService accessControlService;
    @InjectMocks private FormSubmissionService service;

    private FormSubmissionEntity submission;

    @BeforeEach
    void 提出者本人の下書きを用意する() {
        submission = FormSubmissionEntity.builder().id(200L).templateId(100L)
                .scopeType("TEAM").scopeId(1L).submittedBy(10L).build();
        given(submissionRepository.findByIdAndSubmittedBy(200L, 10L)).willReturn(Optional.of(submission));
    }

    @Test
    void 同じ添付keyを再送すると値IDを保持し除去した署名だけ解放する() {
        FormSubmissionValueEntity kept = value(301L, "kept", FormFieldType.FILE);
        FormSubmissionValueEntity removed = value(302L, "removed", FormFieldType.SIGNATURE);
        given(submissionRepository.save(submission)).willReturn(submission);
        given(valueRepository.findBySubmissionId(200L)).willReturn(List.of(kept, removed));
        given(valueRepository.saveAll(any())).willAnswer(invocation -> {
            List<FormSubmissionValueEntity> values = invocation.getArgument(0);
            assertThat(values).singleElement().satisfies(value -> {
                assertThat(value.getId()).isEqualTo(301L);
                assertThat(value.getFileKey()).isEqualTo("kept");
                assertThat(value.getFieldKey()).isEqualTo("renamed");
            });
            return values;
        });

        service.updateSubmission(200L, 10L, new UpdateFormSubmissionRequest(false,
                List.of(request("renamed", "kept"))));

        verify(storageAclService).claimPending("kept", 10L, StorageAclScope.team(1L),
                new StorageAclContentReference("FORM_SUBMISSION", "200"), binding("301"));
        verify(storageAclService).releaseClaimed("removed", binding("302"));
        verify(storageAclService, never()).releaseClaimed("kept", binding("301"));
        verify(valueRepository).deleteAll(List.of(removed));
        verify(valueRepository, never()).deleteBySubmissionId(any());
        verifyNoInteractions(storageService);
    }

    @Test
    void 空配列への更新は全添付を個別解放する() {
        FormSubmissionValueEntity removed = value(301L, "removed", FormFieldType.FILE);
        given(submissionRepository.save(submission)).willReturn(submission);
        given(valueRepository.findBySubmissionId(200L)).willReturn(List.of(removed));
        given(valueRepository.saveAll(any())).willReturn(List.of());

        service.updateSubmission(200L, 10L, new UpdateFormSubmissionRequest(false, List.of()));

        verify(storageAclService).releaseClaimed("removed", binding("301"));
        verify(valueRepository).deleteAll(List.of(removed));
    }

    @Test
    void values未指定は添付を変更しない() {
        given(submissionRepository.save(submission)).willReturn(submission);
        given(valueRepository.findBySubmissionId(200L)).willReturn(List.of(value(301L, "kept", FormFieldType.FILE)));

        service.updateSubmission(200L, 10L, new UpdateFormSubmissionRequest(false, null));

        verifyNoInteractions(storageAclService);
        verify(valueRepository, never()).saveAll(any());
        verify(valueRepository, never()).deleteAll(any());
    }

    @Test
    void 同じkeyを二つの値へ複製する更新は保存前に拒否する() {
        given(submissionRepository.save(submission)).willReturn(submission);
        given(valueRepository.findBySubmissionId(200L)).willReturn(List.of(value(301L, "kept", FormFieldType.FILE)));

        assertThatThrownBy(() -> service.updateSubmission(200L, 10L,
                new UpdateFormSubmissionRequest(false, List.of(request("a", "kept"), request("b", "kept")))))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(storageAclService);
        verify(valueRepository, never()).saveAll(any());
        verify(valueRepository, never()).deleteAll(any());
    }

    @Test
    void 提出削除は添付ごとに解放してから論理削除する() {
        given(valueRepository.findBySubmissionId(200L)).willReturn(List.of(value(301L, "removed", FormFieldType.FILE)));

        service.deleteSubmission(200L, 10L);

        verify(storageAclService).releaseClaimed("removed", binding("301"));
        assertThat(submission.getDeletedAt()).isNotNull();
        verifyNoInteractions(storageService);
    }

    @Test
    void 解放失敗時は提出の論理削除に進まない() {
        given(valueRepository.findBySubmissionId(200L)).willReturn(List.of(value(301L, "removed", FormFieldType.FILE)));
        org.mockito.Mockito.doThrow(new BusinessException(com.mannschaft.app.common.storage.StorageErrorCode.ACL_NOT_FOUND))
                .when(storageAclService).releaseClaimed("removed", binding("301"));

        assertThatThrownBy(() -> service.deleteSubmission(200L, 10L)).isInstanceOf(BusinessException.class);

        assertThat(submission.getDeletedAt()).isNull();
        verify(submissionRepository, never()).save(any());
    }

    @Test
    void 大会再提出は操作担当が変わっても添付IDと元提出者の所有権を保持する() {
        // 通常更新の本人検索は使わず、認可済みの大会提出枠から提出を取得する入口。
        org.mockito.Mockito.reset(submissionRepository);
        java.util.UUID requirementId = java.util.UUID.randomUUID();
        com.mannschaft.app.forms.entity.FormTemplateEntity template =
                com.mannschaft.app.forms.entity.FormTemplateEntity.builder()
                        .scopeType("TEAM").scopeId(1L).name("大会書類").createdBy(10L).build();
        template.publish();
        given(templateService.getTemplateEntity(100L)).willReturn(template);
        given(submissionRepository.findByTournamentSubmissionRequirementIdAndScopeTypeAndScopeId(
                requirementId, "TEAM", 1L)).willReturn(Optional.of(submission));
        given(submissionRepository.save(submission)).willReturn(submission);
        given(valueRepository.findBySubmissionId(200L)).willReturn(List.of(value(301L, "kept", FormFieldType.FILE)));
        given(valueRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

        service.createSubmissionForRequirement("TEAM", 1L, 20L, requirementId,
                new com.mannschaft.app.forms.dto.CreateFormSubmissionRequest(
                        100L, false, List.of(request("original", "kept"))));

        verify(storageAclService).claimPending("kept", 10L, StorageAclScope.team(1L),
                new StorageAclContentReference("FORM_SUBMISSION", "200"), binding("301"));
        verify(storageAclService, never()).releaseClaimed(any(), any());
        verify(valueRepository, never()).deleteBySubmissionId(any());
    }

    private FormSubmissionValueEntity value(Long id, String key, FormFieldType type) {
        return FormSubmissionValueEntity.builder().id(id).submissionId(200L).fieldKey("original")
                .fieldType(type).fileKey(key).build();
    }

    private SubmissionValueRequest request(String fieldKey, String fileKey) {
        return new SubmissionValueRequest(fieldKey, "FILE", null, null, null, fileKey, false);
    }

    private StorageAclAttachmentBinding binding(String id) {
        return new StorageAclAttachmentBinding("FORM_SUBMISSION_VALUE", id);
    }
}
