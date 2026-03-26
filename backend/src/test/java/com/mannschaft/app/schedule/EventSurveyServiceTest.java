package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.dto.CreateSurveyRequest;
import com.mannschaft.app.schedule.dto.EventSurveyResponse;
import com.mannschaft.app.schedule.dto.SurveyResponseDetailResponse;
import com.mannschaft.app.schedule.dto.SurveyResponseRequest;
import com.mannschaft.app.schedule.entity.EventSurveyEntity;
import com.mannschaft.app.schedule.entity.EventSurveyResponseEntity;
import com.mannschaft.app.schedule.repository.EventSurveyRepository;
import com.mannschaft.app.schedule.repository.EventSurveyResponseRepository;
import com.mannschaft.app.schedule.service.EventSurveyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link EventSurveyService} の単体テスト。
 * アンケート設問の一括作成、回答登録、回答集計を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventSurveyService 単体テスト")
class EventSurveyServiceTest {

    @Mock
    private EventSurveyRepository surveyRepository;

    @Mock
    private EventSurveyResponseRepository responseRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private EventSurveyService eventSurveyService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 1L;
    private static final Long SURVEY_ID = 10L;
    private static final Long USER_ID = 100L;

    private EventSurveyEntity createSurveyEntity(Long scheduleId) {
        return EventSurveyEntity.builder()
                .scheduleId(scheduleId)
                .question("参加可能な時間帯は？")
                .questionType(SurveyQuestionType.SELECT)
                .options("[\"午前\",\"午後\"]")
                .isRequired(true)
                .sortOrder(1)
                .build();
    }

    private CreateSurveyRequest createSurveyRequest() {
        return new CreateSurveyRequest(
                "参加可能な時間帯は？",
                "SELECT",
                List.of("午前", "午後"),
                true,
                1);
    }

    // ========================================
    // createSurveys
    // ========================================

    @Nested
    @DisplayName("createSurveys")
    class CreateSurveys {

        @Test
        @DisplayName("設問作成_正常_設問一覧を返す")
        void 設問作成_正常_設問一覧を返す() throws Exception {
            // given
            given(surveyRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
            given(objectMapper.writeValueAsString(any())).willReturn("[\"午前\",\"午後\"]");
            given(surveyRepository.save(any(EventSurveyEntity.class)))
                    .willAnswer(invocation -> {
                        EventSurveyEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = EventSurveyEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });

            List<CreateSurveyRequest> requests = List.of(createSurveyRequest());

            // when
            List<EventSurveyResponse> result = eventSurveyService.createSurveys(SCHEDULE_ID, requests);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getQuestion()).isEqualTo("参加可能な時間帯は？");
            verify(surveyRepository).save(any(EventSurveyEntity.class));
        }

        @Test
        @DisplayName("設問作成_上限超過_例外スロー")
        void 設問作成_上限超過_例外スロー() {
            // given
            given(surveyRepository.countByScheduleId(SCHEDULE_ID)).willReturn(9L);
            List<CreateSurveyRequest> requests = List.of(createSurveyRequest(), createSurveyRequest());

            // when & then
            assertThatThrownBy(() -> eventSurveyService.createSurveys(SCHEDULE_ID, requests))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.MAX_SURVEYS_EXCEEDED);
        }

        @Test
        @DisplayName("設問作成_既存0件で10件追加_正常")
        void 設問作成_既存0件で10件追加_正常() throws Exception {
            // given
            given(surveyRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
            given(objectMapper.writeValueAsString(any())).willReturn("[]");
            given(surveyRepository.save(any(EventSurveyEntity.class)))
                    .willAnswer(invocation -> {
                        EventSurveyEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m = EventSurveyEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });

            List<CreateSurveyRequest> requests = java.util.stream.IntStream.range(0, 10)
                    .mapToObj(i -> createSurveyRequest())
                    .toList();

            // when
            List<EventSurveyResponse> result = eventSurveyService.createSurveys(SCHEDULE_ID, requests);

            // then
            assertThat(result).hasSize(10);
        }
    }

    // ========================================
    // getSurveys
    // ========================================

    @Nested
    @DisplayName("getSurveys")
    class GetSurveys {

        @Test
        @DisplayName("設問取得_正常_設問一覧を返す")
        void 設問取得_正常_設問一覧を返す() {
            // given
            EventSurveyEntity survey = createSurveyEntity(SCHEDULE_ID);
            given(surveyRepository.findByScheduleIdOrderBySortOrderAsc(SCHEDULE_ID))
                    .willReturn(List.of(survey));

            // when
            List<EventSurveyResponse> result = eventSurveyService.getSurveys(SCHEDULE_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getQuestion()).isEqualTo("参加可能な時間帯は？");
        }

        @Test
        @DisplayName("設問取得_設問なし_空リストを返す")
        void 設問取得_設問なし_空リストを返す() {
            // given
            given(surveyRepository.findByScheduleIdOrderBySortOrderAsc(SCHEDULE_ID))
                    .willReturn(List.of());

            // when
            List<EventSurveyResponse> result = eventSurveyService.getSurveys(SCHEDULE_ID);

            // then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // respondToSurvey
    // ========================================

    @Nested
    @DisplayName("respondToSurvey")
    class RespondToSurvey {

        @Test
        @DisplayName("回答登録_新規_保存される")
        void 回答登録_新規_保存される() throws Exception {
            // given
            EventSurveyEntity survey = createSurveyEntity(SCHEDULE_ID);
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.of(survey));
            given(responseRepository.findByEventSurveyIdAndUserId(SURVEY_ID, USER_ID))
                    .willReturn(Optional.empty());
            given(objectMapper.writeValueAsString(any())).willReturn("[\"午前\"]");

            SurveyResponseRequest req = new SurveyResponseRequest(SURVEY_ID, "テスト回答", List.of("午前"));

            // when
            eventSurveyService.respondToSurvey(SURVEY_ID, USER_ID, req);

            // then
            verify(responseRepository).save(any(EventSurveyResponseEntity.class));
        }

        @Test
        @DisplayName("回答登録_既存更新_上書きされる")
        void 回答登録_既存更新_上書きされる() throws Exception {
            // given
            EventSurveyEntity survey = createSurveyEntity(SCHEDULE_ID);
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.of(survey));

            EventSurveyResponseEntity existing = EventSurveyResponseEntity.builder()
                    .eventSurveyId(SURVEY_ID)
                    .userId(USER_ID)
                    .answerText("旧回答")
                    .build();
            given(responseRepository.findByEventSurveyIdAndUserId(SURVEY_ID, USER_ID))
                    .willReturn(Optional.of(existing));
            given(objectMapper.writeValueAsString(any())).willReturn("[\"午後\"]");

            SurveyResponseRequest req = new SurveyResponseRequest(SURVEY_ID, "新回答", List.of("午後"));

            // when
            eventSurveyService.respondToSurvey(SURVEY_ID, USER_ID, req);

            // then
            verify(responseRepository).save(any(EventSurveyResponseEntity.class));
        }

        @Test
        @DisplayName("回答登録_設問不在_例外スロー")
        void 回答登録_設問不在_例外スロー() {
            // given
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.empty());

            SurveyResponseRequest req = new SurveyResponseRequest(SURVEY_ID, "テスト", null);

            // when & then
            assertThatThrownBy(() -> eventSurveyService.respondToSurvey(SURVEY_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SURVEY_NOT_FOUND);
        }
    }

    // ========================================
    // getSurveyResults
    // ========================================

    @Nested
    @DisplayName("getSurveyResults")
    class GetSurveyResults {

        @Test
        @DisplayName("回答集計_正常_設問別回答マップを返す")
        void 回答集計_正常_設問別回答マップを返す() {
            // given
            EventSurveyEntity survey = createSurveyEntity(SCHEDULE_ID);
            given(surveyRepository.findByScheduleIdOrderBySortOrderAsc(SCHEDULE_ID))
                    .willReturn(List.of(survey));

            EventSurveyResponseEntity response = EventSurveyResponseEntity.builder()
                    .eventSurveyId(SURVEY_ID)
                    .userId(USER_ID)
                    .answerText("テスト回答")
                    .build();
            // survey.getId() is null (not persisted), so use null key
            given(responseRepository.findByEventSurveyIdOrderByCreatedAtAsc(survey.getId()))
                    .willReturn(List.of(response));

            // when
            Map<Long, List<SurveyResponseDetailResponse>> results =
                    eventSurveyService.getSurveyResults(SCHEDULE_ID);

            // then
            assertThat(results).containsKey(survey.getId());
        }

        @Test
        @DisplayName("回答集計_設問なし_空マップを返す")
        void 回答集計_設問なし_空マップを返す() {
            // given
            given(surveyRepository.findByScheduleIdOrderBySortOrderAsc(SCHEDULE_ID))
                    .willReturn(List.of());

            // when
            Map<Long, List<SurveyResponseDetailResponse>> results =
                    eventSurveyService.getSurveyResults(SCHEDULE_ID);

            // then
            assertThat(results).isEmpty();
        }
    }
}
