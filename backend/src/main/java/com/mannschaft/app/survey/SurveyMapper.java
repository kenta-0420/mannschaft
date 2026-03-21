package com.mannschaft.app.survey;

import com.mannschaft.app.survey.dto.OptionResponse;
import com.mannschaft.app.survey.dto.QuestionResponse;
import com.mannschaft.app.survey.dto.SurveyResponse;
import com.mannschaft.app.survey.dto.SurveyResponseEntry;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyOptionEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * アンケート機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface SurveyMapper {

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "resultsVisibility", expression = "java(entity.getResultsVisibility().name())")
    @Mapping(target = "distributionMode", expression = "java(entity.getDistributionMode().name())")
    SurveyResponse toSurveyResponse(SurveyEntity entity);

    List<SurveyResponse> toSurveyResponseList(List<SurveyEntity> entities);

    @Mapping(target = "questionType", expression = "java(entity.getQuestionType().name())")
    @Mapping(target = "options", ignore = true)
    QuestionResponse toQuestionResponse(SurveyQuestionEntity entity);

    List<QuestionResponse> toQuestionResponseList(List<SurveyQuestionEntity> entities);

    OptionResponse toOptionResponse(SurveyOptionEntity entity);

    List<OptionResponse> toOptionResponseList(List<SurveyOptionEntity> entities);

    SurveyResponseEntry toResponseEntry(SurveyResponseEntity entity);

    List<SurveyResponseEntry> toResponseEntryList(List<SurveyResponseEntity> entities);

    /**
     * 設問と選択肢を組み合わせた QuestionResponse を生成する。
     *
     * @param entity  設問エンティティ
     * @param options 選択肢リスト
     * @return 設問レスポンス
     */
    default QuestionResponse toQuestionResponseWithOptions(SurveyQuestionEntity entity,
                                                           List<SurveyOptionEntity> options) {
        return new QuestionResponse(
                entity.getId(),
                entity.getSurveyId(),
                entity.getQuestionType().name(),
                entity.getQuestionText(),
                entity.getIsRequired(),
                entity.getDisplayOrder(),
                entity.getMaxSelections(),
                entity.getScaleMin(),
                entity.getScaleMax(),
                entity.getScaleMinLabel(),
                entity.getScaleMaxLabel(),
                entity.getCreatedAt(),
                toOptionResponseList(options)
        );
    }
}
