package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.SurveyQuestionType;
import com.mannschaft.app.schedule.dto.CreateSurveyRequest;
import com.mannschaft.app.schedule.dto.EventSurveyResponse;
import com.mannschaft.app.schedule.dto.SurveyResponseDetailResponse;
import com.mannschaft.app.schedule.dto.SurveyResponseRequest;
import com.mannschaft.app.schedule.entity.EventSurveyEntity;
import com.mannschaft.app.schedule.entity.EventSurveyResponseEntity;
import com.mannschaft.app.schedule.repository.EventSurveyRepository;
import com.mannschaft.app.schedule.repository.EventSurveyResponseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * アンケート設問・回答管理サービス。設問の一括作成、回答登録、回答集計を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventSurveyService {

    private static final int MAX_SURVEYS_PER_SCHEDULE = 10;

    private final EventSurveyRepository surveyRepository;
    private final EventSurveyResponseRepository responseRepository;
    private final ObjectMapper objectMapper;

    /**
     * アンケート設問を一括作成する。最大10件まで。
     *
     * @param scheduleId スケジュールID
     * @param requests   設問作成リクエストリスト
     * @return 作成された設問一覧
     */
    @Transactional
    public List<EventSurveyResponse> createSurveys(Long scheduleId, List<CreateSurveyRequest> requests) {
        long existingCount = surveyRepository.countByScheduleId(scheduleId);
        if (existingCount + requests.size() > MAX_SURVEYS_PER_SCHEDULE) {
            throw new BusinessException(ScheduleErrorCode.MAX_SURVEYS_EXCEEDED);
        }

        List<EventSurveyResponse> responses = new ArrayList<>();

        for (CreateSurveyRequest req : requests) {
            String optionsJson = serializeOptions(req.getOptions());

            EventSurveyEntity survey = EventSurveyEntity.builder()
                    .scheduleId(scheduleId)
                    .question(req.getQuestion())
                    .questionType(SurveyQuestionType.valueOf(req.getQuestionType()))
                    .options(optionsJson)
                    .isRequired(req.getIsRequired())
                    .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                    .build();

            survey = surveyRepository.save(survey);
            responses.add(toSurveyResponse(survey));
        }

        log.info("アンケート設問作成: scheduleId={}, 件数={}", scheduleId, requests.size());
        return responses;
    }

    /**
     * スケジュールに紐付くアンケート設問一覧を取得する。
     *
     * @param scheduleId スケジュールID
     * @return 設問一覧
     */
    public List<EventSurveyResponse> getSurveys(Long scheduleId) {
        return surveyRepository.findByScheduleIdOrderBySortOrderAsc(scheduleId).stream()
                .map(this::toSurveyResponse)
                .toList();
    }

    /**
     * アンケート設問に回答する。既存回答がある場合は上書きする。
     *
     * @param surveyId 設問ID
     * @param userId   ユーザーID
     * @param req      回答リクエスト
     */
    @Transactional
    public void respondToSurvey(Long surveyId, Long userId, SurveyResponseRequest req) {
        surveyRepository.findById(surveyId)
                .orElseThrow(() -> new BusinessException(ScheduleErrorCode.SURVEY_NOT_FOUND));

        String answerOptionsJson = serializeOptions(req.getAnswerOptions());

        EventSurveyResponseEntity response = responseRepository
                .findByEventSurveyIdAndUserId(surveyId, userId)
                .map(existing -> existing.toBuilder()
                        .answerText(req.getAnswerText())
                        .answerOptions(answerOptionsJson)
                        .build())
                .orElse(EventSurveyResponseEntity.builder()
                        .eventSurveyId(surveyId)
                        .userId(userId)
                        .answerText(req.getAnswerText())
                        .answerOptions(answerOptionsJson)
                        .build());

        responseRepository.save(response);
    }

    /**
     * スケジュールのアンケート回答結果を設問別に取得する。
     *
     * @param scheduleId スケジュールID
     * @return 設問別回答集計（設問ID → 回答リスト）
     */
    public Map<Long, List<SurveyResponseDetailResponse>> getSurveyResults(Long scheduleId) {
        List<EventSurveyEntity> surveys = surveyRepository
                .findByScheduleIdOrderBySortOrderAsc(scheduleId);

        Map<Long, List<SurveyResponseDetailResponse>> results = new HashMap<>();

        for (EventSurveyEntity survey : surveys) {
            List<SurveyResponseDetailResponse> responses = responseRepository
                    .findByEventSurveyIdOrderByCreatedAtAsc(survey.getId()).stream()
                    .map(r -> new SurveyResponseDetailResponse(
                            survey.getId(),
                            r.getUserId(),
                            r.getAnswerText(),
                            deserializeOptions(r.getAnswerOptions())))
                    .toList();

            results.put(survey.getId(), responses);
        }

        return results;
    }

    // --- プライベートメソッド ---

    /**
     * 選択肢リストをJSON文字列にシリアライズする。
     */
    private String serializeOptions(List<String> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            log.warn("選択肢のシリアライズに失敗: {}", e.getMessage());
            return null;
        }
    }

    /**
     * JSON文字列を選択肢リストにデシリアライズする。
     */
    private List<String> deserializeOptions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("選択肢のデシリアライズに失敗: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * エンティティをアンケート設問レスポンスDTOに変換する。
     */
    private EventSurveyResponse toSurveyResponse(EventSurveyEntity entity) {
        return new EventSurveyResponse(
                entity.getId(),
                entity.getQuestion(),
                entity.getQuestionType().name(),
                deserializeOptions(entity.getOptions()),
                entity.getIsRequired(),
                entity.getSortOrder());
    }
}
