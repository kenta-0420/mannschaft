package com.mannschaft.app.survey;

import com.mannschaft.app.survey.dto.OptionResponse;
import com.mannschaft.app.survey.dto.QuestionResponse;
import com.mannschaft.app.survey.dto.SurveyResponse;
import com.mannschaft.app.survey.dto.SurveyResponseEntry;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyOptionEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
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
public class SurveyMapperImpl implements SurveyMapper {

    @Override
    public SurveyResponse toSurveyResponse(SurveyEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        String title = null;
        String description = null;
        Boolean isAnonymous = null;
        Boolean allowMultipleSubmissions = null;
        Boolean autoPostToTimeline = null;
        String seriesId = null;
        String remindBeforeHours = null;
        Integer manualRemindCount = null;
        LocalDateTime startsAt = null;
        LocalDateTime expiresAt = null;
        Integer responseCount = null;
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
        title = entity.getTitle();
        description = entity.getDescription();
        isAnonymous = entity.getIsAnonymous();
        allowMultipleSubmissions = entity.getAllowMultipleSubmissions();
        autoPostToTimeline = entity.getAutoPostToTimeline();
        seriesId = entity.getSeriesId();
        remindBeforeHours = entity.getRemindBeforeHours();
        manualRemindCount = entity.getManualRemindCount();
        startsAt = entity.getStartsAt();
        expiresAt = entity.getExpiresAt();
        responseCount = entity.getResponseCount();
        targetCount = entity.getTargetCount();
        createdBy = entity.getCreatedBy();
        publishedAt = entity.getPublishedAt();
        closedAt = entity.getClosedAt();
        version = entity.getVersion();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();
        String resultsVisibility = entity.getResultsVisibility().name();
        String distributionMode = entity.getDistributionMode().name();

        SurveyResponse surveyResponse = new SurveyResponse( id, scopeType, scopeId, title, description, status, isAnonymous, allowMultipleSubmissions, resultsVisibility, distributionMode, autoPostToTimeline, seriesId, remindBeforeHours, manualRemindCount, startsAt, expiresAt, responseCount, targetCount, createdBy, publishedAt, closedAt, version, createdAt, updatedAt );

        return surveyResponse;
    }

    @Override
    public List<SurveyResponse> toSurveyResponseList(List<SurveyEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SurveyResponse> list = new ArrayList<SurveyResponse>( entities.size() );
        for ( SurveyEntity surveyEntity : entities ) {
            list.add( toSurveyResponse( surveyEntity ) );
        }

        return list;
    }

    @Override
    public QuestionResponse toQuestionResponse(SurveyQuestionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long surveyId = null;
        String questionText = null;
        Boolean isRequired = null;
        Integer displayOrder = null;
        Integer maxSelections = null;
        Integer scaleMin = null;
        Integer scaleMax = null;
        String scaleMinLabel = null;
        String scaleMaxLabel = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        surveyId = entity.getSurveyId();
        questionText = entity.getQuestionText();
        isRequired = entity.getIsRequired();
        displayOrder = entity.getDisplayOrder();
        maxSelections = entity.getMaxSelections();
        scaleMin = entity.getScaleMin();
        scaleMax = entity.getScaleMax();
        scaleMinLabel = entity.getScaleMinLabel();
        scaleMaxLabel = entity.getScaleMaxLabel();
        createdAt = entity.getCreatedAt();

        String questionType = entity.getQuestionType().name();
        List<OptionResponse> options = null;

        QuestionResponse questionResponse = new QuestionResponse( id, surveyId, questionType, questionText, isRequired, displayOrder, maxSelections, scaleMin, scaleMax, scaleMinLabel, scaleMaxLabel, createdAt, options );

        return questionResponse;
    }

    @Override
    public List<QuestionResponse> toQuestionResponseList(List<SurveyQuestionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<QuestionResponse> list = new ArrayList<QuestionResponse>( entities.size() );
        for ( SurveyQuestionEntity surveyQuestionEntity : entities ) {
            list.add( toQuestionResponse( surveyQuestionEntity ) );
        }

        return list;
    }

    @Override
    public OptionResponse toOptionResponse(SurveyOptionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long questionId = null;
        String optionText = null;
        Integer displayOrder = null;

        id = entity.getId();
        questionId = entity.getQuestionId();
        optionText = entity.getOptionText();
        displayOrder = entity.getDisplayOrder();

        OptionResponse optionResponse = new OptionResponse( id, questionId, optionText, displayOrder );

        return optionResponse;
    }

    @Override
    public List<OptionResponse> toOptionResponseList(List<SurveyOptionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<OptionResponse> list = new ArrayList<OptionResponse>( entities.size() );
        for ( SurveyOptionEntity surveyOptionEntity : entities ) {
            list.add( toOptionResponse( surveyOptionEntity ) );
        }

        return list;
    }

    @Override
    public SurveyResponseEntry toResponseEntry(SurveyResponseEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long surveyId = null;
        Long questionId = null;
        Long userId = null;
        Long optionId = null;
        String textResponse = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        surveyId = entity.getSurveyId();
        questionId = entity.getQuestionId();
        userId = entity.getUserId();
        optionId = entity.getOptionId();
        textResponse = entity.getTextResponse();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        SurveyResponseEntry surveyResponseEntry = new SurveyResponseEntry( id, surveyId, questionId, userId, optionId, textResponse, createdAt, updatedAt );

        return surveyResponseEntry;
    }

    @Override
    public List<SurveyResponseEntry> toResponseEntryList(List<SurveyResponseEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SurveyResponseEntry> list = new ArrayList<SurveyResponseEntry>( entities.size() );
        for ( SurveyResponseEntity surveyResponseEntity : entities ) {
            list.add( toResponseEntry( surveyResponseEntity ) );
        }

        return list;
    }
}
