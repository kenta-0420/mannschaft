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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:10+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class FormMapperImpl implements FormMapper {

    @Override
    public FormTemplateResponse toTemplateResponse(FormTemplateEntity entity) {
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
        Boolean requiresApproval = null;
        Long workflowTemplateId = null;
        Boolean isSealOnPdf = null;
        LocalDateTime deadline = null;
        Boolean allowEditAfterSubmit = null;
        Boolean autoFillEnabled = null;
        Integer maxSubmissionsPerUser = null;
        Integer sortOrder = null;
        Long presetId = null;
        Integer submissionCount = null;
        Integer targetCount = null;
        Long createdBy = null;
        LocalDateTime publishedAt = null;
        LocalDateTime closedAt = null;
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
        requiresApproval = entity.getRequiresApproval();
        workflowTemplateId = entity.getWorkflowTemplateId();
        isSealOnPdf = entity.getIsSealOnPdf();
        deadline = entity.getDeadline();
        allowEditAfterSubmit = entity.getAllowEditAfterSubmit();
        autoFillEnabled = entity.getAutoFillEnabled();
        maxSubmissionsPerUser = entity.getMaxSubmissionsPerUser();
        sortOrder = entity.getSortOrder();
        presetId = entity.getPresetId();
        submissionCount = entity.getSubmissionCount();
        targetCount = entity.getTargetCount();
        createdBy = entity.getCreatedBy();
        publishedAt = entity.getPublishedAt();
        closedAt = entity.getClosedAt();
        version = entity.getVersion();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();
        List<FormFieldResponse> fields = null;

        FormTemplateResponse formTemplateResponse = new FormTemplateResponse( id, scopeType, scopeId, name, description, icon, color, status, requiresApproval, workflowTemplateId, isSealOnPdf, deadline, allowEditAfterSubmit, autoFillEnabled, maxSubmissionsPerUser, sortOrder, presetId, submissionCount, targetCount, createdBy, publishedAt, closedAt, version, createdAt, updatedAt, fields );

        return formTemplateResponse;
    }

    @Override
    public FormFieldResponse toFieldResponse(FormTemplateFieldEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long templateId = null;
        String fieldKey = null;
        String fieldLabel = null;
        Boolean isRequired = null;
        Integer sortOrder = null;
        String autoFillKey = null;
        String optionsJson = null;
        String placeholder = null;

        id = entity.getId();
        templateId = entity.getTemplateId();
        fieldKey = entity.getFieldKey();
        fieldLabel = entity.getFieldLabel();
        isRequired = entity.getIsRequired();
        sortOrder = entity.getSortOrder();
        autoFillKey = entity.getAutoFillKey();
        optionsJson = entity.getOptionsJson();
        placeholder = entity.getPlaceholder();

        String fieldType = entity.getFieldType().name();

        FormFieldResponse formFieldResponse = new FormFieldResponse( id, templateId, fieldKey, fieldLabel, fieldType, isRequired, sortOrder, autoFillKey, optionsJson, placeholder );

        return formFieldResponse;
    }

    @Override
    public List<FormFieldResponse> toFieldResponseList(List<FormTemplateFieldEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<FormFieldResponse> list = new ArrayList<FormFieldResponse>( entities.size() );
        for ( FormTemplateFieldEntity formTemplateFieldEntity : entities ) {
            list.add( toFieldResponse( formTemplateFieldEntity ) );
        }

        return list;
    }

    @Override
    public FormSubmissionResponse toSubmissionResponse(FormSubmissionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long templateId = null;
        String scopeType = null;
        Long scopeId = null;
        Long submittedBy = null;
        Long workflowRequestId = null;
        String pdfFileKey = null;
        Integer submissionCountForUser = null;
        Long version = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        templateId = entity.getTemplateId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        submittedBy = entity.getSubmittedBy();
        workflowRequestId = entity.getWorkflowRequestId();
        pdfFileKey = entity.getPdfFileKey();
        submissionCountForUser = entity.getSubmissionCountForUser();
        version = entity.getVersion();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();
        List<SubmissionValueResponse> values = null;

        FormSubmissionResponse formSubmissionResponse = new FormSubmissionResponse( id, templateId, scopeType, scopeId, status, submittedBy, workflowRequestId, pdfFileKey, submissionCountForUser, version, createdAt, updatedAt, values );

        return formSubmissionResponse;
    }

    @Override
    public SubmissionValueResponse toValueResponse(FormSubmissionValueEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long submissionId = null;
        String fieldKey = null;
        String textValue = null;
        BigDecimal numberValue = null;
        LocalDate dateValue = null;
        String fileKey = null;
        Boolean isAutoFilled = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        submissionId = entity.getSubmissionId();
        fieldKey = entity.getFieldKey();
        textValue = entity.getTextValue();
        numberValue = entity.getNumberValue();
        dateValue = entity.getDateValue();
        fileKey = entity.getFileKey();
        isAutoFilled = entity.getIsAutoFilled();
        createdAt = entity.getCreatedAt();

        String fieldType = entity.getFieldType().name();

        SubmissionValueResponse submissionValueResponse = new SubmissionValueResponse( id, submissionId, fieldKey, fieldType, textValue, numberValue, dateValue, fileKey, isAutoFilled, createdAt );

        return submissionValueResponse;
    }

    @Override
    public List<SubmissionValueResponse> toValueResponseList(List<FormSubmissionValueEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SubmissionValueResponse> list = new ArrayList<SubmissionValueResponse>( entities.size() );
        for ( FormSubmissionValueEntity formSubmissionValueEntity : entities ) {
            list.add( toValueResponse( formSubmissionValueEntity ) );
        }

        return list;
    }

    @Override
    public FormPresetResponse toPresetResponse(SystemFormPresetEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String name = null;
        String description = null;
        String category = null;
        String fieldsJson = null;
        String icon = null;
        String color = null;
        Boolean isActive = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        name = entity.getName();
        description = entity.getDescription();
        category = entity.getCategory();
        fieldsJson = entity.getFieldsJson();
        icon = entity.getIcon();
        color = entity.getColor();
        isActive = entity.getIsActive();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        FormPresetResponse formPresetResponse = new FormPresetResponse( id, name, description, category, fieldsJson, icon, color, isActive, createdBy, createdAt, updatedAt );

        return formPresetResponse;
    }

    @Override
    public List<FormPresetResponse> toPresetResponseList(List<SystemFormPresetEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<FormPresetResponse> list = new ArrayList<FormPresetResponse>( entities.size() );
        for ( SystemFormPresetEntity systemFormPresetEntity : entities ) {
            list.add( toPresetResponse( systemFormPresetEntity ) );
        }

        return list;
    }
}
