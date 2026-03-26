package com.mannschaft.app.survey;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.survey.dto.SubmitResponseRequest;
import com.mannschaft.app.survey.dto.SurveyResponseEntry;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import com.mannschaft.app.survey.service.SurveyResponseService;
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
 * {@link SurveyResponseService} の単体テスト。
 * 回答の送信・取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyResponseService 単体テスト")
class SurveyResponseServiceTest {

    @Mock
    private SurveyResponseRepository responseRepository;

    @Mock
    private SurveyTargetRepository targetRepository;

    @Mock
    private SurveyMapper surveyMapper;

    @Mock
    private SurveyService surveyService;

    @InjectMocks
    private SurveyResponseService surveyResponseService;

    private static final Long SURVEY_ID = 100L;
    private static final Long USER_ID = 10L;

    private SurveyEntity createPublishedSurvey() {
        SurveyEntity entity = SurveyEntity.builder()
                .scopeType("TEAM").scopeId(1L).title("テスト")
                .distributionMode(DistributionMode.ALL)
                .allowMultipleSubmissions(false).createdBy(1L).build();
        entity.publish();
        return entity;
    }

    @Nested
    @DisplayName("submitResponse")
    class SubmitResponse {

        @Test
        @DisplayName("回答送信_DRAFT状態_BusinessException")
        void 回答送信_DRAFT状態_BusinessException() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .distributionMode(DistributionMode.ALL).createdBy(1L).build();

            SubmitResponseRequest request = new SubmitResponseRequest(List.of());

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);

            // When & Then
            assertThatThrownBy(() -> surveyResponseService.submitResponse(SURVEY_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.INVALID_SURVEY_STATUS));
        }

        @Test
        @DisplayName("回答送信_重複回答不許可_BusinessException")
        void 回答送信_重複回答不許可_BusinessException() {
            // Given
            SurveyEntity survey = createPublishedSurvey();
            SubmitResponseRequest request = new SubmitResponseRequest(List.of());

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(responseRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> surveyResponseService.submitResponse(SURVEY_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.DUPLICATE_RESPONSE));
        }

        @Test
        @DisplayName("回答送信_配信対象外_BusinessException")
        void 回答送信_配信対象外_BusinessException() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .distributionMode(DistributionMode.TARGETED)
                    .allowMultipleSubmissions(false).createdBy(1L).build();
            survey.publish();

            SubmitResponseRequest request = new SubmitResponseRequest(List.of());

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(targetRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyResponseService.submitResponse(SURVEY_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.NOT_TARGET_USER));
        }
    }

    @Nested
    @DisplayName("getMyResponses")
    class GetMyResponses {

        @Test
        @DisplayName("自分の回答取得_正常_リスト返却")
        void 自分の回答取得_正常_リスト返却() {
            // Given
            List<SurveyResponseEntity> entities = List.of();
            given(responseRepository.findBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(entities);
            given(surveyMapper.toResponseEntryList(entities)).willReturn(List.of());

            // When
            List<SurveyResponseEntry> result = surveyResponseService.getMyResponses(SURVEY_ID, USER_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
