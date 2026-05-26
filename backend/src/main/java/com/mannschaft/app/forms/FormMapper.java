package com.mannschaft.app.forms;

import com.mannschaft.app.forms.dto.FormFieldResponse;
import com.mannschaft.app.forms.dto.FormPresetResponse;
import com.mannschaft.app.forms.dto.FormSubmissionResponse;
import com.mannschaft.app.forms.dto.FormTemplateResponse;
import com.mannschaft.app.forms.dto.SubmissionValueResponse;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import com.mannschaft.app.forms.entity.FormSubmissionValueEntity;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.entity.FormTemplateFieldEntity;
import com.mannschaft.app.forms.entity.SystemFormPresetEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * フォーム機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface FormMapper {

    default FormTemplateResponse toTemplateResponse(FormTemplateEntity entity) {
        return FormTemplateResponse.builder()
                .id(entity.getId())
                .status(entity.getStatus().name())
                .scope(new FormTemplateResponse.FormScopeDto(entity.getScopeType(), entity.getScopeId()))
                .content(new FormTemplateResponse.FormContentDto(
                        entity.getName(), entity.getDescription(), entity.getIcon(),
                        entity.getColor(), entity.getSortOrder()))
                .workflow(new FormTemplateResponse.FormWorkflowDto(
                        entity.getRequiresApproval(), entity.getWorkflowTemplateId(), entity.getIsSealOnPdf()))
                .editPolicy(new FormTemplateResponse.FormEditPolicyDto(
                        entity.getAllowEditAfterSubmit(), entity.getAutoFillEnabled(), entity.getMaxSubmissionsPerUser()))
                .stats(new FormTemplateResponse.FormStatsDto(
                        entity.getSubmissionCount(), entity.getTargetCount(), entity.getPresetId()))
                .timeline(new FormTemplateResponse.FormTimelineDto(
                        entity.getDeadline(), entity.getPublishedAt(), entity.getClosedAt()))
                .audit(new FormTemplateResponse.FormAuditDto(
                        entity.getVersion(), entity.getCreatedBy(), entity.getCreatedAt(), entity.getUpdatedAt()))
                .fields(null)
                .build();
    }

    @Mapping(target = "fieldType", expression = "java(entity.getFieldType().name())")
    FormFieldResponse toFieldResponse(FormTemplateFieldEntity entity);

    List<FormFieldResponse> toFieldResponseList(List<FormTemplateFieldEntity> entities);

    default FormSubmissionResponse toSubmissionResponse(FormSubmissionEntity entity) {
        return FormSubmissionResponse.builder()
                .id(entity.getId())
                .status(entity.getStatus().name())
                .scope(new FormSubmissionResponse.FormScopeDto(entity.getScopeType(), entity.getScopeId()))
                .meta(new FormSubmissionResponse.FormSubmissionMetaDto(
                        entity.getTemplateId(), entity.getSubmittedBy(), entity.getWorkflowRequestId(),
                        entity.getSubmissionCountForUser(), entity.getVersion()))
                .pdf(new FormSubmissionResponse.FormSubmissionPdfDto(entity.getPdfFileKey()))
                .audit(new FormSubmissionResponse.FormSubmissionAuditDto(entity.getCreatedAt(), entity.getUpdatedAt()))
                .values(null)
                .build();
    }

    @Mapping(target = "fieldType", expression = "java(entity.getFieldType().name())")
    SubmissionValueResponse toValueResponse(FormSubmissionValueEntity entity);

    List<SubmissionValueResponse> toValueResponseList(List<FormSubmissionValueEntity> entities);

    FormPresetResponse toPresetResponse(SystemFormPresetEntity entity);

    List<FormPresetResponse> toPresetResponseList(List<SystemFormPresetEntity> entities);

    /**
     * テンプレートエンティティとフィールドリストを組み合わせてレスポンスを生成する。
     */
    default FormTemplateResponse toTemplateResponseWithFields(
            FormTemplateEntity entity, List<FormTemplateFieldEntity> fields) {
        return toTemplateResponse(entity).toBuilder()
                .fields(toFieldResponseList(fields))
                .build();
    }

    /**
     * 提出エンティティと値リストを組み合わせてレスポンスを生成する。
     */
    default FormSubmissionResponse toSubmissionResponseWithValues(
            FormSubmissionEntity entity, List<FormSubmissionValueEntity> values) {
        return toSubmissionResponse(entity).toBuilder()
                .values(toValueResponseList(values))
                .build();
    }
}
