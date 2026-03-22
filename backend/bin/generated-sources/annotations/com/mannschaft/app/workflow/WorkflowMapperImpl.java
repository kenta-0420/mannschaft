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
import com.mannschaft.app.workflow.entity.WorkflowRequestAttachmentEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestCommentEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestStepEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateFieldEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateStepEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:34+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class WorkflowMapperImpl implements WorkflowMapper {

    @Override
    public WorkflowTemplateResponse toTemplateResponse(WorkflowTemplateEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        String name = null;
        String description = null;
        String icon = null;
        String color = null;
        Boolean isSealRequired = null;
        Boolean isActive = null;
        Integer sortOrder = null;
        Long createdBy = null;
        Long version = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        name = entity.getName();
        description = entity.getDescription();
        icon = entity.getIcon();
        color = entity.getColor();
        isSealRequired = entity.getIsSealRequired();
        isActive = entity.getIsActive();
        sortOrder = entity.getSortOrder();
        createdBy = entity.getCreatedBy();
        version = entity.getVersion();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        List<TemplateStepResponse> steps = null;
        List<TemplateFieldResponse> fields = null;

        WorkflowTemplateResponse workflowTemplateResponse = new WorkflowTemplateResponse( id, scopeType, scopeId, name, description, icon, color, isSealRequired, isActive, sortOrder, createdBy, version, createdAt, updatedAt, steps, fields );

        return workflowTemplateResponse;
    }

    @Override
    public TemplateStepResponse toStepResponse(WorkflowTemplateStepEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long templateId = null;
        Integer stepOrder = null;
        String name = null;
        String approverUserIds = null;
        String approverRole = null;
        Short autoApproveDays = null;

        id = entity.getId();
        templateId = entity.getTemplateId();
        stepOrder = entity.getStepOrder();
        name = entity.getName();
        approverUserIds = entity.getApproverUserIds();
        approverRole = entity.getApproverRole();
        autoApproveDays = entity.getAutoApproveDays();

        String approvalType = entity.getApprovalType().name();
        String approverType = entity.getApproverType().name();

        TemplateStepResponse templateStepResponse = new TemplateStepResponse( id, templateId, stepOrder, name, approvalType, approverType, approverUserIds, approverRole, autoApproveDays );

        return templateStepResponse;
    }

    @Override
    public List<TemplateStepResponse> toStepResponseList(List<WorkflowTemplateStepEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<TemplateStepResponse> list = new ArrayList<TemplateStepResponse>( entities.size() );
        for ( WorkflowTemplateStepEntity workflowTemplateStepEntity : entities ) {
            list.add( toStepResponse( workflowTemplateStepEntity ) );
        }

        return list;
    }

    @Override
    public TemplateFieldResponse toFieldResponse(WorkflowTemplateFieldEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long templateId = null;
        String fieldKey = null;
        String fieldLabel = null;
        Boolean isRequired = null;
        Integer sortOrder = null;
        String optionsJson = null;

        id = entity.getId();
        templateId = entity.getTemplateId();
        fieldKey = entity.getFieldKey();
        fieldLabel = entity.getFieldLabel();
        isRequired = entity.getIsRequired();
        sortOrder = entity.getSortOrder();
        optionsJson = entity.getOptionsJson();

        String fieldType = entity.getFieldType().name();

        TemplateFieldResponse templateFieldResponse = new TemplateFieldResponse( id, templateId, fieldKey, fieldLabel, fieldType, isRequired, sortOrder, optionsJson );

        return templateFieldResponse;
    }

    @Override
    public List<TemplateFieldResponse> toFieldResponseList(List<WorkflowTemplateFieldEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<TemplateFieldResponse> list = new ArrayList<TemplateFieldResponse>( entities.size() );
        for ( WorkflowTemplateFieldEntity workflowTemplateFieldEntity : entities ) {
            list.add( toFieldResponse( workflowTemplateFieldEntity ) );
        }

        return list;
    }

    @Override
    public WorkflowRequestResponse toRequestResponse(WorkflowRequestEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long templateId = null;
        String scopeType = null;
        Long scopeId = null;
        String title = null;
        Long requestedBy = null;
        LocalDateTime requestedAt = null;
        Integer currentStepOrder = null;
        String fieldValues = null;
        Long version = null;
        String sourceType = null;
        Long sourceId = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        templateId = entity.getTemplateId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        title = entity.getTitle();
        requestedBy = entity.getRequestedBy();
        requestedAt = entity.getRequestedAt();
        currentStepOrder = entity.getCurrentStepOrder();
        fieldValues = entity.getFieldValues();
        version = entity.getVersion();
        sourceType = entity.getSourceType();
        sourceId = entity.getSourceId();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();
        List<RequestStepResponse> steps = null;

        WorkflowRequestResponse workflowRequestResponse = new WorkflowRequestResponse( id, templateId, scopeType, scopeId, title, status, requestedBy, requestedAt, currentStepOrder, fieldValues, version, sourceType, sourceId, createdAt, updatedAt, steps );

        return workflowRequestResponse;
    }

    @Override
    public RequestStepResponse toRequestStepResponse(WorkflowRequestStepEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long requestId = null;
        Integer stepOrder = null;
        LocalDateTime completedAt = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        requestId = entity.getRequestId();
        stepOrder = entity.getStepOrder();
        completedAt = entity.getCompletedAt();
        createdAt = entity.getCreatedAt();

        String status = entity.getStatus().name();
        List<ApproverResponse> approvers = null;

        RequestStepResponse requestStepResponse = new RequestStepResponse( id, requestId, stepOrder, status, completedAt, createdAt, approvers );

        return requestStepResponse;
    }

    @Override
    public List<RequestStepResponse> toRequestStepResponseList(List<WorkflowRequestStepEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<RequestStepResponse> list = new ArrayList<RequestStepResponse>( entities.size() );
        for ( WorkflowRequestStepEntity workflowRequestStepEntity : entities ) {
            list.add( toRequestStepResponse( workflowRequestStepEntity ) );
        }

        return list;
    }

    @Override
    public ApproverResponse toApproverResponse(WorkflowRequestApproverEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long requestStepId = null;
        Long approverUserId = null;
        LocalDateTime decisionAt = null;
        String decisionComment = null;
        Long sealId = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        requestStepId = entity.getRequestStepId();
        approverUserId = entity.getApproverUserId();
        decisionAt = entity.getDecisionAt();
        decisionComment = entity.getDecisionComment();
        sealId = entity.getSealId();
        createdAt = entity.getCreatedAt();

        String decision = entity.getDecision().name();

        ApproverResponse approverResponse = new ApproverResponse( id, requestStepId, approverUserId, decision, decisionAt, decisionComment, sealId, createdAt );

        return approverResponse;
    }

    @Override
    public List<ApproverResponse> toApproverResponseList(List<WorkflowRequestApproverEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ApproverResponse> list = new ArrayList<ApproverResponse>( entities.size() );
        for ( WorkflowRequestApproverEntity workflowRequestApproverEntity : entities ) {
            list.add( toApproverResponse( workflowRequestApproverEntity ) );
        }

        return list;
    }

    @Override
    public WorkflowCommentResponse toCommentResponse(WorkflowRequestCommentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long requestId = null;
        Long userId = null;
        String body = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        requestId = entity.getRequestId();
        userId = entity.getUserId();
        body = entity.getBody();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        WorkflowCommentResponse workflowCommentResponse = new WorkflowCommentResponse( id, requestId, userId, body, createdAt, updatedAt );

        return workflowCommentResponse;
    }

    @Override
    public List<WorkflowCommentResponse> toCommentResponseList(List<WorkflowRequestCommentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<WorkflowCommentResponse> list = new ArrayList<WorkflowCommentResponse>( entities.size() );
        for ( WorkflowRequestCommentEntity workflowRequestCommentEntity : entities ) {
            list.add( toCommentResponse( workflowRequestCommentEntity ) );
        }

        return list;
    }

    @Override
    public WorkflowAttachmentResponse toAttachmentResponse(WorkflowRequestAttachmentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long requestId = null;
        String fileKey = null;
        String originalFilename = null;
        Long fileSize = null;
        Long uploadedBy = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        requestId = entity.getRequestId();
        fileKey = entity.getFileKey();
        originalFilename = entity.getOriginalFilename();
        fileSize = entity.getFileSize();
        uploadedBy = entity.getUploadedBy();
        createdAt = entity.getCreatedAt();

        WorkflowAttachmentResponse workflowAttachmentResponse = new WorkflowAttachmentResponse( id, requestId, fileKey, originalFilename, fileSize, uploadedBy, createdAt );

        return workflowAttachmentResponse;
    }

    @Override
    public List<WorkflowAttachmentResponse> toAttachmentResponseList(List<WorkflowRequestAttachmentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<WorkflowAttachmentResponse> list = new ArrayList<WorkflowAttachmentResponse>( entities.size() );
        for ( WorkflowRequestAttachmentEntity workflowRequestAttachmentEntity : entities ) {
            list.add( toAttachmentResponse( workflowRequestAttachmentEntity ) );
        }

        return list;
    }
}
