package com.mannschaft.app.forms;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.forms.dto.FormTemplateResponse;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.entity.FormTemplateFieldEntity;
import com.mannschaft.app.forms.repository.FormTemplateFieldRepository;
import com.mannschaft.app.forms.repository.FormTemplateRepository;
import com.mannschaft.app.forms.service.FormTemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link FormTemplateService#duplicateTemplate} の単体テスト（F05.7 Phase 11 第四陣 4-B）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FormTemplateService.duplicateTemplate 単体テスト")
class FormTemplateDuplicateTest {

    @Mock private FormTemplateRepository templateRepository;
    @Mock private FormTemplateFieldRepository fieldRepository;
    @Mock private FormMapper formMapper;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private FormTemplateService templateService;

    @Test
    @DisplayName("複製は DRAFT 状態の新規テンプレートを生成し、フィールドも全て複製する")
    void duplicate_Success() {
        FormTemplateEntity original = FormTemplateEntity.builder()
                .scopeType("teams").scopeId(7L)
                .name("入会申込書").description("元説明")
                .createdBy(1L).build();
        original.publish();
        FormTemplateFieldEntity field = FormTemplateFieldEntity.builder()
                .templateId(100L).fieldKey("name").fieldLabel("氏名")
                .fieldType(FormFieldType.TEXT).sortOrder(0).build();

        given(templateRepository.findByIdAndScopeTypeAndScopeId(anyLong(), anyString(), anyLong()))
                .willReturn(Optional.of(original));
        given(fieldRepository.findByTemplateIdOrderBySortOrderAsc(anyLong()))
                .willReturn(List.of(field));
        given(templateRepository.save(any(FormTemplateEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(fieldRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
        given(formMapper.toTemplateResponseWithFields(any(), any()))
                .willReturn(FormTemplateResponse.builder()
                        .id(101L).status("DRAFT")
                        .scope(new FormTemplateResponse.FormScopeDto("teams", 7L))
                        .content(new FormTemplateResponse.FormContentDto("入会申込書 (コピー)", null, null, null, 0))
                        .workflow(new FormTemplateResponse.FormWorkflowDto(false, null, false))
                        .editPolicy(new FormTemplateResponse.FormEditPolicyDto(false, false, 0))
                        .stats(new FormTemplateResponse.FormStatsDto(0, 0, null))
                        .timeline(new FormTemplateResponse.FormTimelineDto(null, null, null))
                        .audit(new FormTemplateResponse.FormAuditDto(0L, 99L, null, null))
                        .fields(List.of())
                        .build());

        FormTemplateResponse response = templateService.duplicateTemplate("teams", 7L, 100L, 99L);

        ArgumentCaptor<FormTemplateEntity> captor = ArgumentCaptor.forClass(FormTemplateEntity.class);
        verify(templateRepository).save(captor.capture());
        FormTemplateEntity saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("入会申込書 (コピー)");
        assertThat(saved.getStatus()).isEqualTo(FormStatus.DRAFT);
        assertThat(saved.getCreatedBy()).isEqualTo(99L);
        assertThat(response.getStatus()).isEqualTo("DRAFT");
    }
}
