package com.mannschaft.app.workflow;

import com.mannschaft.app.workflow.dto.ApproverResponse;
import com.mannschaft.app.workflow.dto.RequestStepResponse;
import com.mannschaft.app.workflow.dto.TemplateFieldResponse;
import com.mannschaft.app.workflow.dto.TemplateStepResponse;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentResponse;
import com.mannschaft.app.workflow.dto.WorkflowCommentResponse;
import com.mannschaft.app.workflow.dto.WorkflowRequestResponse;
import com.mannschaft.app.workflow.dto.WorkflowTemplateResponse;
import com.mannschaft.app.workflow.entity.WorkflowRequestApproverEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestCommentEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestAttachmentEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestStepEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateFieldEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateStepEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * ワークフロー機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface WorkflowMapper {

    @Mapping(target = "steps", ignore = true)
    @Mapping(target = "fields", ignore = true)
    WorkflowTemplateResponse toTemplateResponse(WorkflowTemplateEntity entity);

    @Mapping(target = "approvalType", expression = "java(entity.getApprovalType().name())")
    @Mapping(target = "approverType", expression = "java(entity.getApproverType().name())")
    TemplateStepResponse toStepResponse(WorkflowTemplateStepEntity entity);

    List<TemplateStepResponse> toStepResponseList(List<WorkflowTemplateStepEntity> entities);

    @Mapping(target = "fieldType", expression = "java(entity.getFieldType().name())")
    TemplateFieldResponse toFieldResponse(WorkflowTemplateFieldEntity entity);

    List<TemplateFieldResponse> toFieldResponseList(List<WorkflowTemplateFieldEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "steps", ignore = true)
    WorkflowRequestResponse toRequestResponse(WorkflowRequestEntity entity);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "approvers", ignore = true)
    RequestStepResponse toRequestStepResponse(WorkflowRequestStepEntity entity);

    List<RequestStepResponse> toRequestStepResponseList(List<WorkflowRequestStepEntity> entities);

    @Mapping(target = "decision", expression = "java(entity.getDecision().name())")
    ApproverResponse toApproverResponse(WorkflowRequestApproverEntity entity);

    List<ApproverResponse> toApproverResponseList(List<WorkflowRequestApproverEntity> entities);

    WorkflowCommentResponse toCommentResponse(WorkflowRequestCommentEntity entity);

    List<WorkflowCommentResponse> toCommentResponseList(List<WorkflowRequestCommentEntity> entities);

    WorkflowAttachmentResponse toAttachmentResponse(WorkflowRequestAttachmentEntity entity);

    List<WorkflowAttachmentResponse> toAttachmentResponseList(List<WorkflowRequestAttachmentEntity> entities);

    /**
     * テンプレートレスポンスにステップとフィールドを含めて組み立てる。
     */
    default WorkflowTemplateResponse toTemplateDetailResponse(
            WorkflowTemplateEntity entity,
            List<WorkflowTemplateStepEntity> steps,
            List<WorkflowTemplateFieldEntity> fields) {
        return new WorkflowTemplateResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getName(),
                entity.getDescription(),
                entity.getIcon(),
                entity.getColor(),
                entity.getIsSealRequired(),
                entity.getIsActive(),
                entity.getSortOrder(),
                entity.getCreatedBy(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                toStepResponseList(steps),
                toFieldResponseList(fields));
    }

    /**
     * 申請レスポンスにステップ・承認者情報を含めて組み立てる。
     */
    default WorkflowRequestResponse toRequestDetailResponse(
            WorkflowRequestEntity entity,
            List<RequestStepResponse> stepResponses) {
        return new WorkflowRequestResponse(
                entity.getId(),
                entity.getTemplateId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getTitle(),
                entity.getStatus().name(),
                entity.getRequestedBy(),
                entity.getRequestedAt(),
                entity.getCurrentStepOrder(),
                entity.getFieldValues(),
                entity.getVersion(),
                entity.getSourceType(),
                entity.getSourceId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                stepResponses);
    }
}
