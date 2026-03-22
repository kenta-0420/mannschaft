package com.mannschaft.app.chart;

import com.mannschaft.app.chart.dto.ChartBodyMarkResponse;
import com.mannschaft.app.chart.dto.ChartFormulaResponse;
import com.mannschaft.app.chart.dto.ChartPhotoResponse;
import com.mannschaft.app.chart.dto.ChartRecordResponse;
import com.mannschaft.app.chart.dto.CustomFieldResponse;
import com.mannschaft.app.chart.dto.CustomFieldValueResponse;
import com.mannschaft.app.chart.dto.IntakeFormResponse;
import com.mannschaft.app.chart.dto.RecordTemplateResponse;
import com.mannschaft.app.chart.dto.SectionSettingResponse;
import com.mannschaft.app.chart.entity.ChartBodyMarkEntity;
import com.mannschaft.app.chart.entity.ChartCustomFieldEntity;
import com.mannschaft.app.chart.entity.ChartFormulaEntity;
import com.mannschaft.app.chart.entity.ChartIntakeFormEntity;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.entity.ChartRecordTemplateEntity;
import com.mannschaft.app.chart.entity.ChartSectionSettingEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:10+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class ChartMapperImpl implements ChartMapper {

    @Override
    public ChartRecordResponse toChartRecordResponse(ChartRecordEntity entity, String customerDisplayName, String staffDisplayName, Map<String, Boolean> sectionsEnabled, List<CustomFieldValueResponse> customFields, List<ChartPhotoResponse> photos, List<ChartFormulaResponse> formulas, List<ChartBodyMarkResponse> bodyMarks) {
        if ( entity == null && customerDisplayName == null && staffDisplayName == null && sectionsEnabled == null && customFields == null && photos == null && formulas == null && bodyMarks == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        Long customerUserId = null;
        Long staffUserId = null;
        LocalDate visitDate = null;
        String chiefComplaint = null;
        String treatmentNote = null;
        String nextRecommendation = null;
        LocalDate nextVisitRecommendedDate = null;
        String allergyInfo = null;
        Boolean isSharedToCustomer = null;
        Boolean isPinned = null;
        Long version = null;
        LocalDateTime createdAt = null;
        if ( entity != null ) {
            id = entity.getId();
            teamId = entity.getTeamId();
            customerUserId = entity.getCustomerUserId();
            staffUserId = entity.getStaffUserId();
            visitDate = entity.getVisitDate();
            chiefComplaint = entity.getChiefComplaint();
            treatmentNote = entity.getTreatmentNote();
            nextRecommendation = entity.getNextRecommendation();
            nextVisitRecommendedDate = entity.getNextVisitRecommendedDate();
            allergyInfo = entity.getAllergyInfo();
            isSharedToCustomer = entity.getIsSharedToCustomer();
            isPinned = entity.getIsPinned();
            version = entity.getVersion();
            createdAt = entity.getCreatedAt();
        }
        String customerDisplayName1 = null;
        customerDisplayName1 = customerDisplayName;
        String staffDisplayName1 = null;
        staffDisplayName1 = staffDisplayName;
        Map<String, Boolean> sectionsEnabled1 = null;
        Map<String, Boolean> map = sectionsEnabled;
        if ( map != null ) {
            sectionsEnabled1 = new LinkedHashMap<String, Boolean>( map );
        }
        List<CustomFieldValueResponse> customFields1 = null;
        List<CustomFieldValueResponse> list = customFields;
        if ( list != null ) {
            customFields1 = new ArrayList<CustomFieldValueResponse>( list );
        }
        List<ChartPhotoResponse> photos1 = null;
        List<ChartPhotoResponse> list1 = photos;
        if ( list1 != null ) {
            photos1 = new ArrayList<ChartPhotoResponse>( list1 );
        }
        List<ChartFormulaResponse> formulas1 = null;
        List<ChartFormulaResponse> list2 = formulas;
        if ( list2 != null ) {
            formulas1 = new ArrayList<ChartFormulaResponse>( list2 );
        }
        List<ChartBodyMarkResponse> bodyMarks1 = null;
        List<ChartBodyMarkResponse> list3 = bodyMarks;
        if ( list3 != null ) {
            bodyMarks1 = new ArrayList<ChartBodyMarkResponse>( list3 );
        }

        ChartRecordResponse chartRecordResponse = new ChartRecordResponse( id, teamId, customerUserId, customerDisplayName1, staffUserId, staffDisplayName1, visitDate, chiefComplaint, treatmentNote, nextRecommendation, nextVisitRecommendedDate, allergyInfo, isSharedToCustomer, isPinned, version, sectionsEnabled1, customFields1, photos1, formulas1, bodyMarks1, createdAt );

        return chartRecordResponse;
    }

    @Override
    public ChartBodyMarkResponse toBodyMarkResponse(ChartBodyMarkEntity entity) {
        if ( entity == null ) {
            return null;
        }

        BigDecimal xPosition = null;
        BigDecimal yPosition = null;
        Long id = null;
        String bodyPart = null;
        String markType = null;
        Integer severity = null;
        String note = null;

        xPosition = entity.getXPosition();
        yPosition = entity.getYPosition();
        id = entity.getId();
        bodyPart = entity.getBodyPart();
        markType = entity.getMarkType();
        severity = entity.getSeverity();
        note = entity.getNote();

        ChartBodyMarkResponse chartBodyMarkResponse = new ChartBodyMarkResponse( id, bodyPart, xPosition, yPosition, markType, severity, note );

        return chartBodyMarkResponse;
    }

    @Override
    public List<ChartBodyMarkResponse> toBodyMarkResponseList(List<ChartBodyMarkEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ChartBodyMarkResponse> list = new ArrayList<ChartBodyMarkResponse>( entities.size() );
        for ( ChartBodyMarkEntity chartBodyMarkEntity : entities ) {
            list.add( toBodyMarkResponse( chartBodyMarkEntity ) );
        }

        return list;
    }

    @Override
    public ChartFormulaResponse toFormulaResponse(ChartFormulaEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String productName = null;
        String ratio = null;
        Integer processingTimeMinutes = null;
        String temperature = null;
        LocalDate patchTestDate = null;
        String patchTestResult = null;
        String note = null;
        Integer sortOrder = null;

        id = entity.getId();
        productName = entity.getProductName();
        ratio = entity.getRatio();
        processingTimeMinutes = entity.getProcessingTimeMinutes();
        temperature = entity.getTemperature();
        patchTestDate = entity.getPatchTestDate();
        patchTestResult = entity.getPatchTestResult();
        note = entity.getNote();
        sortOrder = entity.getSortOrder();

        ChartFormulaResponse chartFormulaResponse = new ChartFormulaResponse( id, productName, ratio, processingTimeMinutes, temperature, patchTestDate, patchTestResult, note, sortOrder );

        return chartFormulaResponse;
    }

    @Override
    public List<ChartFormulaResponse> toFormulaResponseList(List<ChartFormulaEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ChartFormulaResponse> list = new ArrayList<ChartFormulaResponse>( entities.size() );
        for ( ChartFormulaEntity chartFormulaEntity : entities ) {
            list.add( toFormulaResponse( chartFormulaEntity ) );
        }

        return list;
    }

    @Override
    public IntakeFormResponse toIntakeFormResponse(ChartIntakeFormEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long chartRecordId = null;
        String formType = null;
        String content = null;
        Long electronicSealId = null;
        LocalDateTime signedAt = null;
        Boolean isInitial = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        chartRecordId = entity.getChartRecordId();
        formType = entity.getFormType();
        content = entity.getContent();
        electronicSealId = entity.getElectronicSealId();
        signedAt = entity.getSignedAt();
        isInitial = entity.getIsInitial();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        IntakeFormResponse intakeFormResponse = new IntakeFormResponse( id, chartRecordId, formType, content, electronicSealId, signedAt, isInitial, createdAt, updatedAt );

        return intakeFormResponse;
    }

    @Override
    public List<IntakeFormResponse> toIntakeFormResponseList(List<ChartIntakeFormEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<IntakeFormResponse> list = new ArrayList<IntakeFormResponse>( entities.size() );
        for ( ChartIntakeFormEntity chartIntakeFormEntity : entities ) {
            list.add( toIntakeFormResponse( chartIntakeFormEntity ) );
        }

        return list;
    }

    @Override
    public SectionSettingResponse toSectionSettingResponse(ChartSectionSettingEntity entity) {
        if ( entity == null ) {
            return null;
        }

        String sectionType = null;
        Boolean isEnabled = null;

        sectionType = entity.getSectionType();
        isEnabled = entity.getIsEnabled();

        SectionSettingResponse sectionSettingResponse = new SectionSettingResponse( sectionType, isEnabled );

        return sectionSettingResponse;
    }

    @Override
    public List<SectionSettingResponse> toSectionSettingResponseList(List<ChartSectionSettingEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SectionSettingResponse> list = new ArrayList<SectionSettingResponse>( entities.size() );
        for ( ChartSectionSettingEntity chartSectionSettingEntity : entities ) {
            list.add( toSectionSettingResponse( chartSectionSettingEntity ) );
        }

        return list;
    }

    @Override
    public CustomFieldResponse toCustomFieldResponse(ChartCustomFieldEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String fieldName = null;
        String fieldType = null;
        String options = null;
        Integer sortOrder = null;
        Boolean isActive = null;

        id = entity.getId();
        fieldName = entity.getFieldName();
        fieldType = entity.getFieldType();
        options = entity.getOptions();
        sortOrder = entity.getSortOrder();
        isActive = entity.getIsActive();

        CustomFieldResponse customFieldResponse = new CustomFieldResponse( id, fieldName, fieldType, options, sortOrder, isActive );

        return customFieldResponse;
    }

    @Override
    public List<CustomFieldResponse> toCustomFieldResponseList(List<ChartCustomFieldEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CustomFieldResponse> list = new ArrayList<CustomFieldResponse>( entities.size() );
        for ( ChartCustomFieldEntity chartCustomFieldEntity : entities ) {
            list.add( toCustomFieldResponse( chartCustomFieldEntity ) );
        }

        return list;
    }

    @Override
    public RecordTemplateResponse toRecordTemplateResponse(ChartRecordTemplateEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String templateName = null;
        String chiefComplaint = null;
        String treatmentNote = null;
        String allergyInfo = null;
        String defaultCustomFields = null;
        Integer sortOrder = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        templateName = entity.getTemplateName();
        chiefComplaint = entity.getChiefComplaint();
        treatmentNote = entity.getTreatmentNote();
        allergyInfo = entity.getAllergyInfo();
        defaultCustomFields = entity.getDefaultCustomFields();
        sortOrder = entity.getSortOrder();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        RecordTemplateResponse recordTemplateResponse = new RecordTemplateResponse( id, templateName, chiefComplaint, treatmentNote, allergyInfo, defaultCustomFields, sortOrder, createdAt, updatedAt );

        return recordTemplateResponse;
    }

    @Override
    public List<RecordTemplateResponse> toRecordTemplateResponseList(List<ChartRecordTemplateEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<RecordTemplateResponse> list = new ArrayList<RecordTemplateResponse>( entities.size() );
        for ( ChartRecordTemplateEntity chartRecordTemplateEntity : entities ) {
            list.add( toRecordTemplateResponse( chartRecordTemplateEntity ) );
        }

        return list;
    }
}
