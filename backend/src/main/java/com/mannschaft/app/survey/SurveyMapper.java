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

import java.util.List;

/**
 * アンケート機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface SurveyMapper {

    default SurveyResponse toSurveyResponse(SurveyEntity entity) {
        return SurveyResponse.builder()
                .id(entity.getId())
                .status(entity.getStatus())
                .scope(new SurveyResponse.SurveyScopeDto(entity.getScopeType(), entity.getScopeId()))
                .content(new SurveyResponse.SurveyContentDto(entity.getTitle(), entity.getDescription()))
                .policy(new SurveyResponse.SurveyPolicyDto(
                        entity.getIsAnonymous(), entity.getAllowMultipleSubmissions(),
                        entity.getResultsVisibility(), entity.getUnrespondedVisibility()))
                .distribution(new SurveyResponse.SurveyDistributionDto(
                        entity.getDistributionMode(), entity.getAutoPostToTimeline(),
                        entity.getSeriesId(), entity.getRemindBeforeHours(), entity.getManualRemindCount(),
                        entity.getIncludeSupporters()))
                .schedule(new SurveyResponse.SurveyScheduleDto(
                        entity.getStartsAt(), entity.getExpiresAt(),
                        entity.getPublishedAt(), entity.getClosedAt()))
                .stats(new SurveyResponse.SurveyStatsDto(entity.getResponseCount(), entity.getTargetCount()))
                .audit(new SurveyResponse.SurveyAuditDto(
                        entity.getVersion(), entity.getCreatedBy(), entity.getCreatedAt(), entity.getUpdatedAt()))
                .build();
    }

    List<SurveyResponse> toSurveyResponseList(List<SurveyEntity> entities);

    default QuestionResponse toQuestionResponse(SurveyQuestionEntity entity) {
        return QuestionResponse.builder()
                .id(entity.getId())
                .surveyId(entity.getSurveyId())
                .questionType(entity.getQuestionType())
                .content(new QuestionResponse.QuestionContentDto(
                        entity.getQuestionText(), entity.getIsRequired(),
                        entity.getDisplayOrder(), entity.getMaxSelections()))
                .scaleConfig(new QuestionResponse.QuestionScaleConfigDto(
                        entity.getScaleMin(), entity.getScaleMax(),
                        entity.getScaleMinLabel(), entity.getScaleMaxLabel()))
                .createdAt(entity.getCreatedAt())
                .options(null)
                .build();
    }

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
        return toQuestionResponse(entity).toBuilder()
                .options(toOptionResponseList(options))
                .build();
    }
}
