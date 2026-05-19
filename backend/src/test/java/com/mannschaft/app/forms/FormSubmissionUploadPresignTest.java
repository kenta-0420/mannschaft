package com.mannschaft.app.forms;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.forms.dto.FormUploadUrlRequest;
import com.mannschaft.app.forms.dto.FormUploadUrlResponse;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import com.mannschaft.app.forms.repository.FormSubmissionRepository;
import com.mannschaft.app.forms.repository.FormSubmissionValueRepository;
import com.mannschaft.app.forms.service.FormSubmissionService;
import com.mannschaft.app.forms.service.FormTemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * {@link FormSubmissionService#presignUploadUrl} の単体テスト（F05.7 Phase 11 第四陣 4-B）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FormSubmissionService.presignUploadUrl 単体テスト")
class FormSubmissionUploadPresignTest {

    @Mock private FormSubmissionRepository submissionRepository;
    @Mock private FormSubmissionValueRepository valueRepository;
    @Mock private FormTemplateService templateService;
    @Mock private FormMapper formMapper;
    @Mock private StorageService storageService;

    @InjectMocks
    private FormSubmissionService formSubmissionService;

    private FormSubmissionEntity draftSubmission() {
        return FormSubmissionEntity.builder()
                .templateId(100L).scopeType("teams").scopeId(7L).submittedBy(10L).build();
    }

    private FormUploadUrlRequest req(String fieldKey, String contentType, long size) {
        FormUploadUrlRequest r = new FormUploadUrlRequest();
        r.setFieldKey(fieldKey);
        r.setFileName("test.png");
        r.setContentType(contentType);
        r.setFileSize(size);
        return r;
    }

    @Test
    @DisplayName("通常添付（PDF / 1MB）は Pre-signed URL を返す")
    void presign_GeneralFile_Ok() {
        given(submissionRepository.findByIdAndSubmittedBy(anyLong(), anyLong()))
                .willReturn(Optional.of(draftSubmission()));
        given(storageService.generateUploadUrl(anyString(), anyString(), any(Duration.class)))
                .willReturn(new PresignedUploadResult("https://signed", "forms/key", 600L));

        FormUploadUrlResponse response = formSubmissionService.presignUploadUrl(
                "teams", 7L, 200L, 10L, req("attachment", "application/pdf", 1_000_000L));

        assertThat(response.getUploadUrl()).isEqualTo("https://signed");
        assertThat(response.getExpiresIn()).isEqualTo(600L);
    }

    @Test
    @DisplayName("署名フィールドの場合 image/png のみ許可・500KB 上限")
    void presign_Signature_PngOnly() {
        given(submissionRepository.findByIdAndSubmittedBy(anyLong(), anyLong()))
                .willReturn(Optional.of(draftSubmission()));
        given(storageService.generateUploadUrl(anyString(), anyString(), any(Duration.class)))
                .willReturn(new PresignedUploadResult("https://signed", "forms/sig", 600L));

        FormUploadUrlResponse response = formSubmissionService.presignUploadUrl(
                "teams", 7L, 200L, 10L, req("user_signature", "image/png", 300_000L));

        assertThat(response.getFileKey()).isEqualTo("forms/sig");
    }

    @Test
    @DisplayName("署名フィールドで JPEG はエラー")
    void presign_Signature_JpegRejected() {
        given(submissionRepository.findByIdAndSubmittedBy(anyLong(), anyLong()))
                .willReturn(Optional.of(draftSubmission()));

        assertThatThrownBy(() -> formSubmissionService.presignUploadUrl(
                "teams", 7L, 200L, 10L, req("signature", "image/jpeg", 100_000L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("一般添付で 11MB はサイズ超過エラー")
    void presign_GeneralFile_SizeExceeded() {
        given(submissionRepository.findByIdAndSubmittedBy(anyLong(), anyLong()))
                .willReturn(Optional.of(draftSubmission()));

        assertThatThrownBy(() -> formSubmissionService.presignUploadUrl(
                "teams", 7L, 200L, 10L, req("attachment", "application/pdf", 11L * 1024 * 1024)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("SUBMITTED 状態の提出には添付不可（編集不可ステータス）")
    void presign_SubmittedStatus_Rejected() {
        FormSubmissionEntity submitted = draftSubmission();
        submitted.submit();
        given(submissionRepository.findByIdAndSubmittedBy(anyLong(), anyLong()))
                .willReturn(Optional.of(submitted));

        assertThatThrownBy(() -> formSubmissionService.presignUploadUrl(
                "teams", 7L, 200L, 10L, req("attachment", "application/pdf", 1_000L)))
                .isInstanceOf(BusinessException.class);
    }
}
