package com.mannschaft.app.survey;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.survey.dto.SurveyResultResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.repository.SurveyOptionRepository;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
import com.mannschaft.app.survey.service.SurveyResultService;
import com.mannschaft.app.survey.service.SurveyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link SurveyResultService} の単体テスト。
 * 結果の集計・閲覧権限管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyResultService 単体テスト")
class SurveyResultServiceTest {

    @Mock
    private SurveyQuestionRepository questionRepository;

    @Mock
    private SurveyOptionRepository optionRepository;

    @Mock
    private SurveyResponseRepository responseRepository;

    @Mock
    private SurveyResultViewerRepository resultViewerRepository;

    @Mock
    private SurveyService surveyService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private SurveyResultService surveyResultService;

    private static final Long SURVEY_ID = 100L;
    private static final Long USER_ID = 10L;

    /**
     * SurveyEntity の BaseEntity.id をリフレクションで設定する。
     */
    private void setEntityId(SurveyEntity entity, Long id) {
        try {
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception ignored) {}
    }

    @Nested
    @DisplayName("getResults")
    class GetResults {

        @Test
        @DisplayName("結果取得_AFTER_RESPONSE_未回答_BusinessException")
        void 結果取得_AFTER_RESPONSE_未回答_BusinessException() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL).createdBy(1L).build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(responseRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyResultService.getResults(SURVEY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.RESULT_ACCESS_DENIED));
        }

        @Test
        @DisplayName("結果取得_AFTER_CLOSE_PUBLISHED状態_BusinessException")
        void 結果取得_AFTER_CLOSE_PUBLISHED状態_BusinessException() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_CLOSE)
                    .distributionMode(DistributionMode.ALL).createdBy(1L).build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);

            // When & Then
            assertThatThrownBy(() -> surveyResultService.getResults(SURVEY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.RESULT_ACCESS_DENIED));
        }

        @Test
        @DisplayName("結果取得_AFTER_RESPONSE_回答済み_正常")
        void 結果取得_AFTER_RESPONSE_回答済み_正常() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL).createdBy(1L).build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(responseRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(true);
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID)).willReturn(List.of());
            given(responseRepository.countDistinctUsersBySurveyId(SURVEY_ID)).willReturn(5L);

            // When
            SurveyResultResponse result = surveyResultService.getResults(SURVEY_ID, USER_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getSurveyId()).isEqualTo(SURVEY_ID);
        }

        @Test
        @DisplayName("結果取得_VIEWERS_ONLY_閲覧者でない_BusinessException")
        void 結果取得_VIEWERS_ONLY_閲覧者でない_BusinessException() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .resultsVisibility(ResultsVisibility.VIEWERS_ONLY)
                    .distributionMode(DistributionMode.ALL).createdBy(999L).build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(resultViewerRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyResultService.getResults(SURVEY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.RESULT_ACCESS_DENIED));
        }
    }
}
