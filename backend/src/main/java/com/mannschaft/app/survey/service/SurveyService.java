package com.mannschaft.app.survey.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.QuestionType;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.SurveyErrorCode;
import com.mannschaft.app.survey.SurveyMapper;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.UnrespondedVisibility;
import com.mannschaft.app.survey.dto.CreateOptionRequest;
import com.mannschaft.app.survey.dto.CreateQuestionRequest;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import com.mannschaft.app.survey.dto.QuestionResponse;
import com.mannschaft.app.survey.dto.RemindResponse;
import com.mannschaft.app.survey.dto.SurveyDetailResponse;
import com.mannschaft.app.survey.dto.SurveyResponse;
import com.mannschaft.app.survey.dto.SurveyStatsResponse;
import com.mannschaft.app.survey.dto.UpdateSurveyRequest;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyOptionEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.entity.SurveyResultViewerEntity;
import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import com.mannschaft.app.survey.repository.SurveyOptionRepository;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * アンケートサービス。アンケートのCRUD・ライフサイクル管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SurveyService {

    /** 督促回数の上限（手動リマインド）。 */
    private static final int MAX_MANUAL_REMIND_COUNT = 3;

    /** 督促のクールダウン時間（時間単位）。 */
    private static final long REMIND_COOLDOWN_HOURS = 24L;

    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository questionRepository;
    private final SurveyOptionRepository optionRepository;
    private final SurveyTargetRepository targetRepository;
    private final SurveyResultViewerRepository resultViewerRepository;
    private final SurveyResponseRepository responseRepository;
    private final SurveyMapper surveyMapper;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final NotificationHelper notificationHelper;

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
                .unrespondedVisibility(request.getUnrespondedVisibility() != null
                        ? UnrespondedVisibility.valueOf(request.getUnrespondedVisibility())
                        : UnrespondedVisibility.CREATOR_AND_ADMIN)
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
        if (request.getUnrespondedVisibility() != null) {
            entity.updateUnrespondedVisibility(
                    UnrespondedVisibility.valueOf(request.getUnrespondedVisibility()));
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
     * 未回答者へ督促通知を送信する（F05.4 督促 API）。
     *
     * <p>認可・状態・クールダウン・上限を順にチェックし、未回答メンバーへ通知を送信する。
     * 通知送信は {@link NotificationHelper#notifyAll} 経由で個別の失敗を握りつつ継続するため、
     * 一部ユーザーへの送信失敗があっても全体ロールバックは発生しない（onboarding 流儀に準拠）。
     * カウンタ更新（{@code lastRemindedAt} / {@code manualRemindCount}）は同一トランザクションで永続化する。</p>
     *
     * <p>母集団の決定:
     * <ul>
     *   <li>{@code survey_targets} に登録があれば、それを母集団とする</li>
     *   <li>未登録（DistributionMode = ALL かつ事前登録なし）の場合は対象0件として扱い、送信スキップ</li>
     * </ul>
     * 既に回答済みのユーザーは除外する。
     * </p>
     *
     * @param surveyId      対象アンケートID
     * @param currentUserId 操作実行者ユーザーID
     * @return 督促結果（送信人数・残回数・案内メッセージ）
     * @throws BusinessException SURVEY_NOT_FOUND / REMIND_PERMISSION_DENIED /
     *                           INVALID_SURVEY_STATUS / REMIND_COOLDOWN_NOT_ELAPSED /
     *                           REMIND_QUOTA_EXCEEDED
     */
    @Transactional
    public RemindResponse remind(Long surveyId, Long currentUserId) {
        SurveyEntity survey = surveyRepository.findById(surveyId)
                .orElseThrow(() -> new BusinessException(SurveyErrorCode.SURVEY_NOT_FOUND));

        // 認可: 作成者 or ADMIN+ のみ督促を送信可能（getRespondents の認可パターンに準拠）
        boolean isCreator = survey.getCreatedBy() != null && survey.getCreatedBy().equals(currentUserId);
        boolean isAdmin = accessControlService.isAdminOrAbove(
                currentUserId, survey.getScopeId(), survey.getScopeType());
        if (!isCreator && !isAdmin) {
            throw new BusinessException(SurveyErrorCode.REMIND_PERMISSION_DENIED);
        }

        // 状態: PUBLISHED のみ督促可能
        if (survey.getStatus() != SurveyStatus.PUBLISHED) {
            throw new BusinessException(SurveyErrorCode.INVALID_SURVEY_STATUS);
        }

        // クールダウン: 前回送信から24時間経過していなければ拒否
        LocalDateTime now = LocalDateTime.now();
        if (survey.getLastRemindedAt() != null
                && Duration.between(survey.getLastRemindedAt(), now).toHours() < REMIND_COOLDOWN_HOURS) {
            throw new BusinessException(SurveyErrorCode.REMIND_COOLDOWN_NOT_ELAPSED);
        }

        // 上限: 手動リマインドは MAX_MANUAL_REMIND_COUNT 回まで
        if (survey.getManualRemindCount() != null
                && survey.getManualRemindCount() >= MAX_MANUAL_REMIND_COUNT) {
            throw new BusinessException(SurveyErrorCode.REMIND_QUOTA_EXCEEDED);
        }

        // 未回答者抽出（母集団は survey_targets。回答済みを除外）
        List<Long> unansweredUserIds = findUnansweredUserIds(surveyId);

        // 通知送信（NotificationHelper.notifyAll が個別の失敗を握りつつ継続する）
        NotificationScopeType notifScope = "TEAM".equals(survey.getScopeType())
                ? NotificationScopeType.TEAM
                : NotificationScopeType.ORGANIZATION;
        if (!unansweredUserIds.isEmpty()) {
            notificationHelper.notifyAll(
                    unansweredUserIds,
                    "SURVEY_RESPONSE_REMINDER",
                    "アンケート未回答のお知らせ",
                    "「" + survey.getTitle() + "」が未回答です。回答にご協力ください。",
                    "SURVEY",
                    surveyId,
                    notifScope,
                    survey.getScopeId(),
                    "/surveys/" + surveyId,
                    currentUserId);
        }

        // カウンタ更新
        survey.recordManualRemind(now);
        surveyRepository.save(survey);

        int remindedCount = unansweredUserIds.size();
        int newCount = survey.getManualRemindCount() != null ? survey.getManualRemindCount() : 0;
        int remainingQuota = MAX_MANUAL_REMIND_COUNT - newCount;
        String message = remindedCount + "名の未回答メンバーにリマインドを送信しました。";

        log.info("アンケート督促: surveyId={}, remindedCount={}, manualRemindCount={}, by={}",
                surveyId, remindedCount, newCount, currentUserId);

        return new RemindResponse(surveyId, remindedCount, remainingQuota, message);
    }

    /**
     * 未回答ユーザーIDを抽出する。母集団は {@code survey_targets}（未登録時は空）。
     */
    private List<Long> findUnansweredUserIds(Long surveyId) {
        List<SurveyTargetEntity> targets = targetRepository.findBySurveyId(surveyId);
        if (targets.isEmpty()) {
            return List.of();
        }
        Set<Long> respondedUserIds = new HashSet<>();
        for (SurveyResponseEntity r : responseRepository.findBySurveyIdOrderByCreatedAtAsc(surveyId)) {
            respondedUserIds.add(r.getUserId());
        }
        List<Long> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (SurveyTargetEntity t : targets) {
            Long uid = t.getUserId();
            if (uid == null || respondedUserIds.contains(uid) || !seen.add(uid)) {
                continue;
            }
            result.add(uid);
        }
        return result;
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
