package com.mannschaft.app.forms;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.forms.dto.FormPdfDownloadUrlResponse;
import com.mannschaft.app.forms.dto.FormPdfGenerateResponse;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.repository.FormSubmissionRepository;
import com.mannschaft.app.forms.repository.FormSubmissionValueRepository;
import com.mannschaft.app.forms.repository.FormTemplateFieldRepository;
import com.mannschaft.app.forms.repository.FormTemplateRepository;
import com.mannschaft.app.forms.service.FormPdfService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link FormPdfService} の単体テスト（F05.7 Phase 11 第四陣 4-B）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FormPdfService 単体テスト")
class FormPdfServiceTest {

    @Mock private FormSubmissionRepository submissionRepository;
    @Mock private FormSubmissionValueRepository valueRepository;
    @Mock private FormTemplateRepository templateRepository;
    @Mock private FormTemplateFieldRepository fieldRepository;
    @Mock private PdfGeneratorService pdfGeneratorService;
    @Mock private StorageService storageService;
    @Mock private AuditLogService auditLogService;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private FormPdfService formPdfService;

    private FormSubmissionEntity submittedSubmission(Long submitter) {
        FormSubmissionEntity e = FormSubmissionEntity.builder()
                .templateId(100L).scopeType("teams").scopeId(7L)
                .submittedBy(submitter).build();
        e.submit();
        return e;
    }

    private FormTemplateEntity template(Long creator) {
        return FormTemplateEntity.builder()
                .scopeType("teams").scopeId(7L)
                .name("入会申込書").createdBy(creator).build();
    }

    @Test
    @DisplayName("提出者本人による PDF 生成は成功し pdfFileKey が記録される")
    void generatePdf_BySubmitter_Success() {
        FormSubmissionEntity submission = submittedSubmission(10L);
        FormTemplateEntity template = template(99L);
        given(submissionRepository.findById(anyLong())).willReturn(Optional.of(submission));
        given(templateRepository.findById(anyLong())).willReturn(Optional.of(template));
        // BaseEntity#id は @GeneratedValue で未 save なら null。anyLong() は null にマッチしないため any() を使う
        given(fieldRepository.findByTemplateIdOrderBySortOrderAsc(any())).willReturn(List.of());
        given(valueRepository.findBySubmissionId(anyLong())).willReturn(List.of());
        given(pdfGeneratorService.generateFromTemplate(anyString(), any(Map.class))).willReturn(new byte[]{1, 2, 3});
        given(submissionRepository.save(any())).willReturn(submission);

        FormPdfGenerateResponse response = formPdfService.generatePdf("teams", 7L, 200L, 10L);

        assertThat(response.getPdfFileKey()).startsWith("forms/teams/7/submissions/200/");
        assertThat(submission.getPdfFileKey()).isEqualTo(response.getPdfFileKey());
        verify(storageService).upload(anyString(), any(byte[].class), anyString());
    }

    @Test
    @DisplayName("テンプレート作成者による PDF 生成も成功する")
    void generatePdf_ByCreator_Success() {
        FormSubmissionEntity submission = submittedSubmission(10L);
        FormTemplateEntity template = template(99L);
        given(submissionRepository.findById(anyLong())).willReturn(Optional.of(submission));
        given(templateRepository.findById(anyLong())).willReturn(Optional.of(template));
        // BaseEntity#id は @GeneratedValue で未 save なら null。anyLong() は null にマッチしないため any() を使う
        given(fieldRepository.findByTemplateIdOrderBySortOrderAsc(any())).willReturn(List.of());
        given(valueRepository.findBySubmissionId(anyLong())).willReturn(List.of());
        given(pdfGeneratorService.generateFromTemplate(anyString(), any(Map.class))).willReturn(new byte[]{1});
        given(submissionRepository.save(any())).willReturn(submission);

        FormPdfGenerateResponse response = formPdfService.generatePdf("teams", 7L, 200L, 99L);

        assertThat(response.getSubmissionId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("DRAFT 状態の提出は PDF 生成不可")
    void generatePdf_DraftStatus_Throws() {
        FormSubmissionEntity draft = FormSubmissionEntity.builder()
                .templateId(100L).scopeType("teams").scopeId(7L).submittedBy(10L).build();
        given(submissionRepository.findById(anyLong())).willReturn(Optional.of(draft));

        assertThatThrownBy(() -> formPdfService.generatePdf("teams", 7L, 200L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PDF");
    }

    @Test
    @DisplayName("提出者でもテンプレート作成者でもないユーザーは PDF 生成不可")
    void generatePdf_UnrelatedUser_Throws() {
        FormSubmissionEntity submission = submittedSubmission(10L);
        FormTemplateEntity template = template(99L);
        given(submissionRepository.findById(anyLong())).willReturn(Optional.of(submission));
        given(templateRepository.findById(anyLong())).willReturn(Optional.of(template));

        assertThatThrownBy(() -> formPdfService.generatePdf("teams", 7L, 200L, 999L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("PDF 未生成の場合 download URL 取得は PDF_NOT_GENERATED")
    void downloadUrl_PdfNotGenerated_Throws() {
        FormSubmissionEntity submission = submittedSubmission(10L);
        FormTemplateEntity template = template(99L);
        given(submissionRepository.findById(anyLong())).willReturn(Optional.of(submission));
        given(templateRepository.findById(anyLong())).willReturn(Optional.of(template));

        assertThatThrownBy(() -> formPdfService.generateDownloadUrl("teams", 7L, 200L, 10L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("PDF 生成済みの場合 Pre-signed URL を返す")
    void downloadUrl_PdfExists_ReturnsUrl() {
        FormSubmissionEntity submission = submittedSubmission(10L);
        submission.setPdfFileKey("forms/teams/7/submissions/200/form_200_1.pdf");
        FormTemplateEntity template = template(99L);
        given(submissionRepository.findById(anyLong())).willReturn(Optional.of(submission));
        given(templateRepository.findById(anyLong())).willReturn(Optional.of(template));
        given(storageService.generateDownloadUrl(anyString(), any(Duration.class)))
                .willReturn("https://example.com/signed");

        FormPdfDownloadUrlResponse response =
                formPdfService.generateDownloadUrl("teams", 7L, 200L, 10L);

        assertThat(response.getDownloadUrl()).isEqualTo("https://example.com/signed");
        assertThat(response.getExpiresIn()).isEqualTo(300L);
    }
}
