package com.mannschaft.app.forms;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import com.mannschaft.app.forms.entity.FormSubmissionValueEntity;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.entity.FormTemplateFieldEntity;
import com.mannschaft.app.forms.repository.FormSubmissionRepository;
import com.mannschaft.app.forms.repository.FormSubmissionValueRepository;
import com.mannschaft.app.forms.repository.FormTemplateFieldRepository;
import com.mannschaft.app.forms.repository.FormTemplateRepository;
import com.mannschaft.app.forms.service.FormCsvExportService;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * {@link FormCsvExportService} の単体テスト（F05.7 Phase 11 第四陣 4-B）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FormCsvExportService 単体テスト")
class FormCsvExportServiceTest {

    @Mock private FormTemplateRepository templateRepository;
    @Mock private FormTemplateFieldRepository fieldRepository;
    @Mock private FormSubmissionRepository submissionRepository;
    @Mock private FormSubmissionValueRepository valueRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private FormCsvExportService csvExportService;

    private FormTemplateEntity template() {
        return FormTemplateEntity.builder()
                .scopeType("teams").scopeId(7L)
                .name("入会申込書").createdBy(1L).build();
    }

    private FormTemplateFieldEntity field(String key, String label, FormFieldType type) {
        return FormTemplateFieldEntity.builder()
                .templateId(100L).fieldKey(key).fieldLabel(label).fieldType(type)
                .sortOrder(0).build();
    }

    @Test
    @DisplayName("提出 0 件でもヘッダー行だけが返る")
    void exportCsv_Empty() {
        given(templateRepository.findByIdAndScopeTypeAndScopeId(anyLong(), anyString(), anyLong()))
                .willReturn(Optional.of(template()));
        given(fieldRepository.findByTemplateIdOrderBySortOrderAsc(anyLong()))
                .willReturn(List.of(field("name", "氏名", FormFieldType.TEXT)));
        given(submissionRepository.findByTemplateIdOrderByCreatedAtDesc(anyLong()))
                .willReturn(List.of());

        String csv = csvExportService.exportSubmissionsCsv("teams", 7L, 100L, 1L);

        assertThat(csv).startsWith("提出ID,提出者ID,ステータス,提出日時,氏名");
        assertThat(csv.lines().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("CSV インジェクション対象の値はシングルクオートで先頭エスケープされる")
    void exportCsv_CsvInjectionEscape() {
        FormSubmissionEntity submission = FormSubmissionEntity.builder()
                .templateId(100L).scopeType("teams").scopeId(7L)
                .submittedBy(10L).build();
        submission.submit();
        FormSubmissionValueEntity value = FormSubmissionValueEntity.builder()
                .submissionId(200L).fieldKey("formula").fieldType(FormFieldType.TEXT)
                .textValue("=cmd|'/C calc'!A1").build();

        given(templateRepository.findByIdAndScopeTypeAndScopeId(anyLong(), anyString(), anyLong()))
                .willReturn(Optional.of(template()));
        given(fieldRepository.findByTemplateIdOrderBySortOrderAsc(anyLong()))
                .willReturn(List.of(field("formula", "計算式", FormFieldType.TEXT)));
        given(submissionRepository.findByTemplateIdOrderByCreatedAtDesc(anyLong()))
                .willReturn(List.of(submission));
        // テスト用エンティティは BaseEntity の id が null のままで save されないため、any() で受ける
        given(valueRepository.findBySubmissionId(any())).willReturn(List.of(value));

        String csv = csvExportService.exportSubmissionsCsv("teams", 7L, 100L, 1L);

        // CSV インジェクション対策: 先頭が = の値は ' で先頭エスケープされる（RFC 4180 準拠）。
        // 値そのものに , や " を含まないため、ダブルクオート囲みは行われない（設計書 §6 / FormCsvExportService.escapeCell）。
        // 期待: 値部分が '=cmd|'/C calc'!A1 になる
        assertThat(csv).contains("'=cmd|'/C calc'!A1");
        assertThat(csv).doesNotContain("\"'=cmd|");
    }

    @Test
    @DisplayName("テンプレートが見つからない場合は TEMPLATE_NOT_FOUND")
    void exportCsv_TemplateNotFound() {
        given(templateRepository.findByIdAndScopeTypeAndScopeId(anyLong(), anyString(), anyLong()))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> csvExportService.exportSubmissionsCsv("teams", 7L, 100L, 1L))
                .isInstanceOf(BusinessException.class);
    }
}
