package com.mannschaft.app.survey.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.QuestionType;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.SurveyErrorCode;
import com.mannschaft.app.survey.SurveyMapper;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.dto.CreateOptionRequest;
import com.mannschaft.app.survey.dto.CreateQuestionRequest;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import com.mannschaft.app.survey.dto.QuestionResponse;
import com.mannschaft.app.survey.dto.SurveyDetailResponse;
import com.mannschaft.app.survey.dto.SurveyResponse;
import com.mannschaft.app.survey.dto.SurveyStatsResponse;
import com.mannschaft.app.survey.dto.UpdateSurveyRequest;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyOptionEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResultViewerEntity;
import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import com.mannschaft.app.survey.repository.SurveyOptionRepository;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * アンケートサービス。アンケートのCRUD・ライフサイクル管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SurveyService {

    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository questionRepository;
    private final SurveyOptionRepository optionRepository;
    private final SurveyTargetRepository targetRepository;
    private final SurveyResultViewerRepository resultViewerRepository;
    private final SurveyMapper surveyMapper;
    private final ObjectMapper objectMapper;

    /**
     * アンケート一覧をページング取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータスフィルタ（null の場合は全件）
     * @param pageable  ページング情報
     * @return アンケートレスポンスのページ
     */
    public Page<SurveyResponse> listSurveys(String scopeType, Long scopeId,
                                             String status, Pageable pageable) {
        Page<SurveyEntity> page;
        if (status != null) {
            SurveyStatus surveyStatus = SurveyStatus.valueOf(status);
            page = surveyRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    scopeType, scopeId, surveyStatus, pageable);
        } else {
            page = surveyRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    scopeType, scopeId, pageable);
        }
        return page.map(surveyMapper::toSurveyResponse);
    }

    /**
     * アンケート詳細を取得する（設問・選択肢を含む）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param surveyId  アンケートID
     * @return アンケート詳細レスポンス
     */
    public SurveyDetailResponse getSurveyDetail(String scopeType, Long scopeId, Long surveyId) {
        SurveyEntity entity = findSurveyOrThrow(scopeType, scopeId, surveyId);
        SurveyResponse surveyResponse = surveyMapper.toSurveyResponse(entity);
        List<QuestionResponse> questions = buildQuestionResponses(surveyId);
        return new SurveyDetailResponse(surveyResponse, questions);
    }

    /**
     * アンケートを作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    作成者ユーザーID
     * @param request   作成リクエスト
     * @return 作成されたアンケート詳細レスポンス
     */
    @Transactional
    public SurveyDetailResponse createSurvey(String scopeType, Long scopeId, Long userId,
                                              CreateSurveyRequest request) {
        validateTimeRange(request.getStartsAt(), request.getExpiresAt());

        String remindJson = serializeRemindHours(request.getRemindBeforeHours());

        SurveyEntity entity = SurveyEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .title(request.getTitle())
                .description(request.getDescription())
                .isAnonymous(request.getIsAnonymous())
                .allowMultipleSubmissions(request.getAllowMultipleSubmissions())
                .resultsVisibility(ResultsVisibility.valueOf(request.getResultsVisibility()))
                .distributionMode(DistributionMode.valueOf(request.getDistributionMode()))
                .autoPostToTimeline(request.getAutoPostToTimeline() != null
                        ? request.getAutoPostToTimeline() : false)
                .seriesId(request.getSeriesId())
                .remindBeforeHours(remindJson)
                .startsAt(request.getStartsAt())
                .expiresAt(request.getExpiresAt())
                .createdBy(userId)
                .build();

        SurveyEntity saved = surveyRepository.save(entity);

        // 設問・選択肢の作成
        if (request.getQuestions() != null) {
            createQuestionsAndOptions(saved.getId(), request.getQuestions());
        }

        // 配信対象の登録
        if (request.getTargetUserIds() != null && !request.getTargetUserIds().isEmpty()) {
            addTargets(saved.getId(), request.getTargetUserIds());
            saved.updateTargetCount(request.getTargetUserIds().size());
            surveyRepository.save(saved);
        }

        // 結果閲覧者の登録
        if (request.getResultViewerUserIds() != null && !request.getResultViewerUserIds().isEmpty()) {
            addResultViewers(saved.getId(), request.getResultViewerUserIds());
        }

        log.info("アンケート作成: scopeType={}, scopeId={}, surveyId={}", scopeType, scopeId, saved.getId());
        return getSurveyDetail(scopeType, scopeId, saved.getId());
    }

    /**
     * アンケートを更新する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param surveyId  アンケートID
     * @param request   更新リクエスト
     * @return 更新されたアンケートレスポンス
     */
    @Transactional
    public SurveyResponse updateSurvey(String scopeType, Long scopeId, Long surveyId,
                                        UpdateSurveyRequest request) {
        SurveyEntity entity = findSurveyOrThrow(scopeType, scopeId, surveyId);

        if (request.getTitle() != null) {
            entity.changeTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            entity.changeDescription(request.getDescription());
        }
        if (request.getIsAnonymous() != null || request.getAllowMultipleSubmissions() != null
                || request.getResultsVisibility() != null || request.getAutoPostToTimeline() != null) {
            entity.updateSettings(
                    request.getIsAnonymous() != null ? request.getIsAnonymous() : entity.getIsAnonymous(),
                    request.getAllowMultipleSubmissions() != null
                            ? request.getAllowMultipleSubmissions() : entity.getAllowMultipleSubmissions(),
                    request.getResultsVisibility() != null
                            ? ResultsVisibility.valueOf(request.getResultsVisibility())
                            : entity.getResultsVisibility(),
                    request.getAutoPostToTimeline() != null
                            ? request.getAutoPostToTimeline() : entity.getAutoPostToTimeline()
            );
        }
        if (request.getStartsAt() != null || request.getExpiresAt() != null) {
            validateTimeRange(
                    request.getStartsAt() != null ? request.getStartsAt() : entity.getStartsAt(),
                    request.getExpiresAt() != null ? request.getExpiresAt() : entity.getExpiresAt()
            );
            entity.updatePeriod(
                    request.getStartsAt() != null ? request.getStartsAt() : entity.getStartsAt(),
                    request.getExpiresAt() != null ? request.getExpiresAt() : entity.getExpiresAt()
            );
        }

        SurveyEntity saved = surveyRepository.save(entity);
        log.info("アンケート更新: surveyId={}", surveyId);
        return surveyMapper.toSurveyResponse(saved);
    }

    /**
     * アンケートを公開する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param surveyId  アンケートID
     * @return 更新されたアンケートレスポンス
     */
    @Transactional
    public SurveyResponse publishSurvey(String scopeType, Long scopeId, Long surveyId) {
        SurveyEntity entity = findSurveyOrThrow(scopeType, scopeId, surveyId);

        if (!entity.isPublishable()) {
            throw new BusinessException(SurveyErrorCode.INVALID_SURVEY_STATUS);
        }

        long questionCount = questionRepository.countBySurveyId(surveyId);
        if (questionCount == 0) {
            throw new BusinessException(SurveyErrorCode.NO_QUESTIONS);
        }

        entity.publish();
        SurveyEntity saved = surveyRepository.save(entity);
        log.info("アンケート公開: surveyId={}", surveyId);
        return surveyMapper.toSurveyResponse(saved);
    }

    /**
     * アンケートを締め切る。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param surveyId  アンケートID
     * @return 更新されたアンケートレスポンス
     */
    @Transactional
    public SurveyResponse closeSurvey(String scopeType, Long scopeId, Long surveyId) {
        SurveyEntity entity = findSurveyOrThrow(scopeType, scopeId, surveyId);

        if (!entity.isClosable()) {
            throw new BusinessException(SurveyErrorCode.INVALID_SURVEY_STATUS);
        }

        entity.close();
        SurveyEntity saved = surveyRepository.save(entity);
        log.info("アンケート締め切り: surveyId={}", surveyId);
        return surveyMapper.toSurveyResponse(saved);
    }

    /**
     * アンケートを論理削除する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param surveyId  アンケートID
     */
    @Transactional
    public void deleteSurvey(String scopeType, Long scopeId, Long surveyId) {
        SurveyEntity entity = findSurveyOrThrow(scopeType, scopeId, surveyId);
        entity.softDelete();
        surveyRepository.save(entity);
        log.info("アンケート削除: surveyId={}", surveyId);
    }

    /**
     * 設問を追加する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param surveyId  アンケートID
     * @param request   設問作成リクエスト
     * @return 設問レスポンス
     */
    @Transactional
    public QuestionResponse addQuestion(String scopeType, Long scopeId, Long surveyId,
                                         CreateQuestionRequest request) {
        findSurveyOrThrow(scopeType, scopeId, surveyId);

        SurveyQuestionEntity question = SurveyQuestionEntity.builder()
                .surveyId(surveyId)
                .questionType(QuestionType.valueOf(request.getQuestionType()))
                .questionText(request.getQuestionText())
                .isRequired(request.getIsRequired())
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0)
                .maxSelections(request.getMaxSelections())
                .scaleMin(request.getScaleMin())
                .scaleMax(request.getScaleMax())
                .scaleMinLabel(request.getScaleMinLabel())
                .scaleMaxLabel(request.getScaleMaxLabel())
                .build();

        SurveyQuestionEntity savedQuestion = questionRepository.save(question);

        List<SurveyOptionEntity> options = new ArrayList<>();
        if (request.getOptions() != null) {
            for (CreateOptionRequest optReq : request.getOptions()) {
                SurveyOptionEntity option = SurveyOptionEntity.builder()
                        .questionId(savedQuestion.getId())
                        .optionText(optReq.getOptionText())
                        .displayOrder(optReq.getDisplayOrder() != null ? optReq.getDisplayOrder() : 0)
                        .build();
                options.add(optionRepository.save(option));
            }
        }

        log.info("設問追加: surveyId={}, questionId={}", surveyId, savedQuestion.getId());
        return surveyMapper.toQuestionResponseWithOptions(savedQuestion, options);
    }

    /**
     * 設問を削除する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param surveyId   アンケートID
     * @param questionId 設問ID
     */
    @Transactional
    public void deleteQuestion(String scopeType, Long scopeId, Long surveyId, Long questionId) {
        findSurveyOrThrow(scopeType, scopeId, surveyId);
        SurveyQuestionEntity question = questionRepository.findById(questionId)
                .orElseThrow(() -> new BusinessException(SurveyErrorCode.QUESTION_NOT_FOUND));

        if (!question.getSurveyId().equals(surveyId)) {
            throw new BusinessException(SurveyErrorCode.QUESTION_NOT_FOUND);
        }

        optionRepository.deleteByQuestionId(questionId);
        questionRepository.delete(question);
        log.info("設問削除: surveyId={}, questionId={}", surveyId, questionId);
    }

    /**
     * 配信対象を追加する。
     *
     * @param surveyId アンケートID
     * @param userIds  ユーザーIDリスト
     */
    @Transactional
    public void addTargets(Long surveyId, List<Long> userIds) {
        for (Long userId : userIds) {
            if (!targetRepository.existsBySurveyIdAndUserId(surveyId, userId)) {
                SurveyTargetEntity target = SurveyTargetEntity.builder()
                        .surveyId(surveyId)
                        .userId(userId)
                        .build();
                targetRepository.save(target);
            }
        }
        long count = targetRepository.countBySurveyId(surveyId);
        surveyRepository.findById(surveyId).ifPresent(survey -> {
            survey.updateTargetCount((int) count);
            surveyRepository.save(survey);
        });
    }

    /**
     * 結果閲覧者を追加する。
     *
     * @param surveyId アンケートID
     * @param userIds  ユーザーIDリスト
     */
    @Transactional
    public void addResultViewers(Long surveyId, List<Long> userIds) {
        for (Long userId : userIds) {
            if (!resultViewerRepository.existsBySurveyIdAndUserId(surveyId, userId)) {
                SurveyResultViewerEntity viewer = SurveyResultViewerEntity.builder()
                        .surveyId(surveyId)
                        .userId(userId)
                        .build();
                resultViewerRepository.save(viewer);
            }
        }
    }

    /**
     * アンケート統計を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return アンケート統計レスポンス
     */
    public SurveyStatsResponse getStats(String scopeType, Long scopeId) {
        long draft = surveyRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, SurveyStatus.DRAFT);
        long published = surveyRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, SurveyStatus.PUBLISHED);
        long closed = surveyRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, SurveyStatus.CLOSED);
        long archived = surveyRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, SurveyStatus.ARCHIVED);
        long total = draft + published + closed + archived;
        return new SurveyStatsResponse(total, draft, published, closed, archived);
    }

    /**
     * アンケートを取得するヘルパー。内部メソッドとして公開する。
     *
     * @param surveyId アンケートID
     * @return アンケートエンティティ
     */
    public SurveyEntity findSurveyEntityOrThrow(Long surveyId) {
        return surveyRepository.findById(surveyId)
                .orElseThrow(() -> new BusinessException(SurveyErrorCode.SURVEY_NOT_FOUND));
    }

    /**
     * アンケートを取得する。存在しない場合は例外をスローする。
     */
    private SurveyEntity findSurveyOrThrow(String scopeType, Long scopeId, Long surveyId) {
        return surveyRepository.findByIdAndScopeTypeAndScopeId(surveyId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(SurveyErrorCode.SURVEY_NOT_FOUND));
    }

    /**
     * 設問と選択肢のレスポンスリストを構築する。
     */
    private List<QuestionResponse> buildQuestionResponses(Long surveyId) {
        List<SurveyQuestionEntity> questions = questionRepository.findBySurveyIdOrderByDisplayOrderAsc(surveyId);
        List<QuestionResponse> responses = new ArrayList<>();
        for (SurveyQuestionEntity question : questions) {
            List<SurveyOptionEntity> options = optionRepository.findByQuestionIdOrderByDisplayOrderAsc(question.getId());
            responses.add(surveyMapper.toQuestionResponseWithOptions(question, options));
        }
        return responses;
    }

    /**
     * 設問と選択肢を一括作成する。
     */
    private void createQuestionsAndOptions(Long surveyId, List<CreateQuestionRequest> questionRequests) {
        for (int i = 0; i < questionRequests.size(); i++) {
            CreateQuestionRequest qReq = questionRequests.get(i);
            SurveyQuestionEntity question = SurveyQuestionEntity.builder()
                    .surveyId(surveyId)
                    .questionType(QuestionType.valueOf(qReq.getQuestionType()))
                    .questionText(qReq.getQuestionText())
                    .isRequired(qReq.getIsRequired())
                    .displayOrder(qReq.getDisplayOrder() != null ? qReq.getDisplayOrder() : i)
                    .maxSelections(qReq.getMaxSelections())
                    .scaleMin(qReq.getScaleMin())
                    .scaleMax(qReq.getScaleMax())
                    .scaleMinLabel(qReq.getScaleMinLabel())
                    .scaleMaxLabel(qReq.getScaleMaxLabel())
                    .build();
            SurveyQuestionEntity savedQ = questionRepository.save(question);

            if (qReq.getOptions() != null) {
                for (int j = 0; j < qReq.getOptions().size(); j++) {
                    CreateOptionRequest optReq = qReq.getOptions().get(j);
                    SurveyOptionEntity option = SurveyOptionEntity.builder()
                            .questionId(savedQ.getId())
                            .optionText(optReq.getOptionText())
                            .displayOrder(optReq.getDisplayOrder() != null ? optReq.getDisplayOrder() : j)
                            .build();
                    optionRepository.save(option);
                }
            }
        }
    }

    /**
     * 開始時刻と終了時刻の整合性を検証する。
     */
    private void validateTimeRange(java.time.LocalDateTime startsAt, java.time.LocalDateTime expiresAt) {
        if (startsAt != null && expiresAt != null && !startsAt.isBefore(expiresAt)) {
            throw new BusinessException(SurveyErrorCode.INVALID_TIME_RANGE);
        }
    }

    /**
     * リマインド時間リストをJSON文字列にシリアライズする。
     */
    private String serializeRemindHours(List<Integer> hours) {
        if (hours == null || hours.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(hours);
        } catch (JsonProcessingException e) {
            log.warn("リマインド時間のシリアライズに失敗: {}", e.getMessage());
            return null;
        }
    }
}
