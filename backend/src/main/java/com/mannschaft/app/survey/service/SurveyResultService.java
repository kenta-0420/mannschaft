package com.mannschaft.app.survey.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.survey.QuestionType;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.SurveyErrorCode;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.dto.SurveyResultResponse;
import com.mannschaft.app.survey.dto.SurveyResultResponse.OptionResultResponse;
import com.mannschaft.app.survey.dto.SurveyResultResponse.QuestionResultResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyOptionEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.repository.SurveyOptionRepository;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * アンケート結果サービス。結果の集計・閲覧権限管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SurveyResultService {

    private final SurveyQuestionRepository questionRepository;
    private final SurveyOptionRepository optionRepository;
    private final SurveyResponseRepository responseRepository;
    private final SurveyResultViewerRepository resultViewerRepository;
    private final SurveyService surveyService;

    /**
     * アンケート結果を取得する。閲覧権限チェックを行う。
     *
     * @param surveyId アンケートID
     * @param userId   閲覧者ユーザーID
     * @return アンケート結果レスポンス
     */
    public SurveyResultResponse getResults(Long surveyId, Long userId) {
        SurveyEntity survey = surveyService.findSurveyEntityOrThrow(surveyId);

        validateResultAccess(survey, userId);

        return buildResultResponse(survey);
    }

    /**
     * 結果閲覧権限を検証する。
     */
    private void validateResultAccess(SurveyEntity survey, Long userId) {
        ResultsVisibility visibility = survey.getResultsVisibility();

        switch (visibility) {
            case AFTER_RESPONSE -> {
                boolean hasResponded = responseRepository.existsBySurveyIdAndUserId(survey.getId(), userId);
                if (!hasResponded) {
                    throw new BusinessException(SurveyErrorCode.RESULT_ACCESS_DENIED);
                }
            }
            case AFTER_CLOSE -> {
                if (survey.getStatus() != SurveyStatus.CLOSED
                        && survey.getStatus() != SurveyStatus.ARCHIVED) {
                    throw new BusinessException(SurveyErrorCode.RESULT_ACCESS_DENIED);
                }
            }
            case ADMINS_ONLY -> {
                // TODO: ロール検証はSpring Security統合時に実装
                if (!survey.getCreatedBy().equals(userId)) {
                    throw new BusinessException(SurveyErrorCode.RESULT_ACCESS_DENIED);
                }
            }
            case VIEWERS_ONLY -> {
                boolean isViewer = resultViewerRepository.existsBySurveyIdAndUserId(survey.getId(), userId);
                boolean isCreator = survey.getCreatedBy() != null && survey.getCreatedBy().equals(userId);
                if (!isViewer && !isCreator) {
                    throw new BusinessException(SurveyErrorCode.RESULT_ACCESS_DENIED);
                }
            }
        }
    }

    /**
     * アンケート結果レスポンスを構築する。
     */
    private SurveyResultResponse buildResultResponse(SurveyEntity survey) {
        List<SurveyQuestionEntity> questions =
                questionRepository.findBySurveyIdOrderByDisplayOrderAsc(survey.getId());

        long totalRespondents = responseRepository.countDistinctUsersBySurveyId(survey.getId());

        List<QuestionResultResponse> questionResults = new ArrayList<>();
        for (SurveyQuestionEntity question : questions) {
            questionResults.add(buildQuestionResult(survey.getId(), question, totalRespondents));
        }

        return new SurveyResultResponse(
                survey.getId(),
                survey.getTitle(),
                survey.getResponseCount(),
                survey.getTargetCount(),
                questionResults
        );
    }

    /**
     * 設問ごとの結果を構築する。
     */
    private QuestionResultResponse buildQuestionResult(Long surveyId, SurveyQuestionEntity question,
                                                        long totalRespondents) {
        List<OptionResultResponse> optionResults = new ArrayList<>();
        List<String> textResponses = new ArrayList<>();

        if (question.getQuestionType() == QuestionType.SINGLE_CHOICE
                || question.getQuestionType() == QuestionType.MULTIPLE_CHOICE) {
            List<SurveyOptionEntity> options =
                    optionRepository.findByQuestionIdOrderByDisplayOrderAsc(question.getId());

            for (SurveyOptionEntity option : options) {
                long count = responseRepository.countBySurveyIdAndQuestionIdAndOptionId(
                        surveyId, question.getId(), option.getId());
                double percentage = totalRespondents > 0
                        ? (double) count / totalRespondents * 100.0 : 0.0;
                optionResults.add(new OptionResultResponse(
                        option.getId(), option.getOptionText(), count, percentage));
            }
        }

        if (question.getQuestionType() == QuestionType.FREE_TEXT
                || question.getQuestionType() == QuestionType.SCALE) {
            List<SurveyResponseEntity> responses =
                    responseRepository.findBySurveyIdAndQuestionId(surveyId, question.getId());
            for (SurveyResponseEntity resp : responses) {
                if (resp.getTextResponse() != null) {
                    textResponses.add(resp.getTextResponse());
                }
            }
        }

        return new QuestionResultResponse(
                question.getId(),
                question.getQuestionText(),
                question.getQuestionType().name(),
                optionResults,
                textResponses
        );
    }
}
