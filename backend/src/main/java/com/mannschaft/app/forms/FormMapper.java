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

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "fields", ignore = true)
    FormTemplateResponse toTemplateResponse(FormTemplateEntity entity);

    @Mapping(target = "fieldType", expression = "java(entity.getFieldType().name())")
    FormFieldResponse toFieldResponse(FormTemplateFieldEntity entity);

    List<FormFieldResponse> toFieldResponseList(List<FormTemplateFieldEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "values", ignore = true)
    FormSubmissionResponse toSubmissionResponse(FormSubmissionEntity entity);

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
        FormTemplateResponse response = toTemplateResponse(entity);
        List<FormFieldResponse> fieldResponses = toFieldResponseList(fields);
        return new FormTemplateResponse(
                response.getId(),
                response.getScopeType(),
                response.getScopeId(),
                response.getName(),
                response.getDescription(),
                response.getIcon(),
                response.getColor(),
                response.getStatus(),
                response.getRequiresApproval(),
                response.getWorkflowTemplateId(),
                response.getIsSealOnPdf(),
                response.getDeadline(),
                response.getAllowEditAfterSubmit(),
                response.getAutoFillEnabled(),
                response.getMaxSubmissionsPerUser(),
                response.getSortOrder(),
                response.getPresetId(),
                response.getSubmissionCount(),
                response.getTargetCount(),
                response.getCreatedBy(),
                response.getPublishedAt(),
                response.getClosedAt(),
                response.getVersion(),
                response.getCreatedAt(),
                response.getUpdatedAt(),
                fieldResponses
        );
    }

    /**
     * 提出エンティティと値リストを組み合わせてレスポンスを生成する。
     */
    default FormSubmissionResponse toSubmissionResponseWithValues(
            FormSubmissionEntity entity, List<FormSubmissionValueEntity> values) {
        FormSubmissionResponse response = toSubmissionResponse(entity);
        List<SubmissionValueResponse> valueResponses = toValueResponseList(values);
        return new FormSubmissionResponse(
                response.getId(),
                response.getTemplateId(),
                response.getScopeType(),
                response.getScopeId(),
                response.getStatus(),
                response.getSubmittedBy(),
                response.getWorkflowRequestId(),
                response.getPdfFileKey(),
                response.getSubmissionCountForUser(),
                response.getVersion(),
                response.getCreatedAt(),
                response.getUpdatedAt(),
                valueResponses
        );
    }
}
