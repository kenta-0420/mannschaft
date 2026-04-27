package com.mannschaft.app.survey;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.survey.dto.SurveyResponse;
import com.mannschaft.app.survey.dto.SurveyStatsResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.repository.SurveyOptionRepository;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.service.SurveyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SurveyService} の単体テスト。
 * アンケートのCRUD・ライフサイクル管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyService 単体テスト")
class SurveyServiceTest {

    @Mock
    private SurveyRepository surveyRepository;

    @Mock
    private SurveyQuestionRepository questionRepository;

    @Mock
    private SurveyOptionRepository optionRepository;

    @Mock
    private SurveyMapper surveyMapper;

    @InjectMocks
    private SurveyService surveyService;

    private static final Long SURVEY_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final String SCOPE_TYPE = "TEAM";

    private SurveyEntity createDraftSurvey() {
        return SurveyEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).title("テストアンケート")
                .description("説明").isAnonymous(false).allowMultipleSubmissions(false)
                .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                .distributionMode(DistributionMode.ALL)
                .createdBy(USER_ID).build();
    }

    private SurveyEntity createPublishedSurvey() {
        SurveyEntity entity = createDraftSurvey();
        entity.publish();
        return entity;
    }

    private SurveyResponse createSurveyResponse() {
        return new SurveyResponse(SURVEY_ID, SCOPE_TYPE, SCOPE_ID, "テストアンケート",
                "説明", "DRAFT", false, false, "AFTER_RESPONSE", "ALL",
                "CREATOR_AND_ADMIN",
                false, null, null, 0, null, null, 0, 0, USER_ID, null, null, null, null, null);
    }

    @Nested
    @DisplayName("publishSurvey")
    class PublishSurvey {

        @Test
        @DisplayName("アンケート公開_正常_PUBLISHED状態に遷移")
        void アンケート公開_正常_PUBLISHED状態に遷移() {
            // Given
            SurveyEntity entity = createDraftSurvey();
            SurveyResponse response = createSurveyResponse();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(questionRepository.countBySurveyId(SURVEY_ID)).willReturn(3L);
            given(surveyRepository.save(entity)).willReturn(entity);
            given(surveyMapper.toSurveyResponse(entity)).willReturn(response);

            // When
            surveyService.publishSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(SurveyStatus.PUBLISHED);
        }

        @Test
        @DisplayName("アンケート公開_PUBLISHED状態_BusinessException")
        void アンケート公開_PUBLISHED状態_BusinessException() {
            // Given
            SurveyEntity entity = createPublishedSurvey();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> surveyService.publishSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.INVALID_SURVEY_STATUS));
        }

        @Test
        @DisplayName("アンケート公開_設問なし_BusinessException")
        void アンケート公開_設問なし_BusinessException() {
            // Given
            SurveyEntity entity = createDraftSurvey();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(questionRepository.countBySurveyId(SURVEY_ID)).willReturn(0L);

            // When & Then
            assertThatThrownBy(() -> surveyService.publishSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.NO_QUESTIONS));
        }
    }

    @Nested
    @DisplayName("closeSurvey")
    class CloseSurvey {

        @Test
        @DisplayName("アンケート締め切り_正常_CLOSED状態に遷移")
        void アンケート締め切り_正常_CLOSED状態に遷移() {
            // Given
            SurveyEntity entity = createPublishedSurvey();
            SurveyResponse response = createSurveyResponse();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(surveyRepository.save(entity)).willReturn(entity);
            given(surveyMapper.toSurveyResponse(entity)).willReturn(response);

            // When
            surveyService.closeSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(SurveyStatus.CLOSED);
        }

        @Test
        @DisplayName("アンケート締め切り_DRAFT状態_BusinessException")
        void アンケート締め切り_DRAFT状態_BusinessException() {
            // Given
            SurveyEntity entity = createDraftSurvey();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> surveyService.closeSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.INVALID_SURVEY_STATUS));
        }
    }

    @Nested
    @DisplayName("deleteSurvey")
    class DeleteSurvey {

        @Test
        @DisplayName("アンケート削除_正常_論理削除実行")
        void アンケート削除_正常_論理削除実行() {
            // Given
            SurveyEntity entity = createDraftSurvey();
            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            surveyService.deleteSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(surveyRepository).save(entity);
        }
    }

    @Nested
    @DisplayName("deleteQuestion")
    class DeleteQuestion {

        @Test
        @DisplayName("設問削除_正常_設問と選択肢削除")
        void 設問削除_正常_設問と選択肢削除() {
            // Given
            Long questionId = 50L;
            SurveyEntity survey = createDraftSurvey();
            SurveyQuestionEntity question = SurveyQuestionEntity.builder()
                    .surveyId(SURVEY_ID).questionType(QuestionType.SINGLE_CHOICE)
                    .questionText("質問").isRequired(true).build();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(survey));
            given(questionRepository.findById(questionId)).willReturn(Optional.of(question));

            // When
            surveyService.deleteQuestion(SCOPE_TYPE, SCOPE_ID, SURVEY_ID, questionId);

            // Then
            verify(optionRepository).deleteByQuestionId(questionId);
            verify(questionRepository).delete(question);
        }

        @Test
        @DisplayName("設問削除_存在しない_BusinessException")
        void 設問削除_存在しない_BusinessException() {
            // Given
            SurveyEntity survey = createDraftSurvey();
            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(survey));
            given(questionRepository.findById(999L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> surveyService.deleteQuestion(SCOPE_TYPE, SCOPE_ID, SURVEY_ID, 999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.QUESTION_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("統計取得_正常_カウント集計")
        void 統計取得_正常_カウント集計() {
            // Given
            given(surveyRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, SurveyStatus.DRAFT)).willReturn(2L);
            given(surveyRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, SurveyStatus.PUBLISHED)).willReturn(3L);
            given(surveyRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, SurveyStatus.CLOSED)).willReturn(1L);
            given(surveyRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, SurveyStatus.ARCHIVED)).willReturn(0L);

            // When
            SurveyStatsResponse result = surveyService.getStats(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getTotal()).isEqualTo(6L);
            assertThat(result.getDraft()).isEqualTo(2L);
            assertThat(result.getPublished()).isEqualTo(3L);
        }
    }

}
