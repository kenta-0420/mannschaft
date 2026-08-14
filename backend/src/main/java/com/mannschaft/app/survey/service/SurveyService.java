package com.mannschaft.app.survey.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.SurveyErrorCode;
import com.mannschaft.app.survey.SurveyMapper;
import com.mannschaft.app.survey.SurveyNotificationType;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.UnrespondedVisibility;
import com.mannschaft.app.survey.event.SurveyCreatedEvent;
import com.mannschaft.app.survey.event.SurveyPublishedEvent;
import com.mannschaft.app.survey.event.SurveyStatusChangedEvent;
import com.mannschaft.app.survey.dto.CreateOptionRequest;
import com.mannschaft.app.survey.dto.CreateQuestionRequest;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import com.mannschaft.app.survey.dto.DuplicateSurveyRequest;
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
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final SurveyResponseRepository responseRepository;
    private final SurveyMapper surveyMapper;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final UserRoleRepository userRoleRepository;
    private final NotificationHelper notificationHelper;
    private final ApplicationEventPublisher eventPublisher;
    private final OrganizationMembershipService organizationMembershipService;
    /** 結果閲覧可否の唯一の判定点（結果取得 API の 403 と共用。Issue #2779）。 */
    private final SurveyResultAccessPolicy resultAccessPolicy;

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
        // 軍議③ F00 漏洩根治: 本体一覧はスコープ所属者のみ。非所属は COMMON_002(403)。
        // GET /surveys（team/org 版）は認可ゲートが無く、認証済みかつ他スコープの slug + surveyId を
        // 知る任意ユーザーが本体（設問・選択肢）を 200 で取得・列挙できる漏洩があった（回覧板 F00 と同型）。
        // checkMembershipOrDescendant(..., includeSupporters=true) = 会員/応援者/(ORGANIZATION 時)配下ツリー
        // 所属を許可し、非所属のみ弾く。ContentVisibilityChecker.canView(SURVEY,...) は結果専用のため
        // 本体ガードには流用しない（DRAFT 非作成者等を誤 deny する）。手本: CirculationService.listDocuments。
        accessControlService.checkMembershipOrDescendant(
                SecurityUtils.getCurrentUserId(), scopeId, scopeType, true);

        Page<SurveyEntity> page;
        if (status != null) {
            // follow-up④: SurveyStatus.valueOf(status) は不正値で IllegalArgumentException → 500 に
            // 落ちていた。クライアント入力エラーなので parseEnumOrThrow で 400（INVALID_ENUM_VALUE）に変換する。
            // null は「フィルタなし（全件）」として従来どおり通す（null で 400 にしない）。
            SurveyStatus surveyStatus = parseEnumOrThrow(SurveyStatus.class, status, "status");
            page = surveyRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    scopeType, scopeId, surveyStatus, pageable);
        } else {
            page = surveyRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    scopeType, scopeId, pageable);
        }
        return page.map(surveyMapper::toSurveyResponse);
    }

    /**
     * F22.1 第二波: 指定スコープで当該ユーザーが「未回答」の公開中アンケートを取得する。
     *
     * <p>横スワイプ・ダッシュボードの統合「要対応」集計（{@code ScopeActionRequiredFacade}）から
     * 呼ばれる読み取り専用メソッド。<b>per-scope 認可をこのメソッド内で必ず通す</b>
     * （{@link AccessControlService#checkMembership}）。非所属ユーザーは {@code COMMON_002}
     * で弾かれる（集計バイパス禁止・02 §3.4）。</p>
     *
     * <p>未回答判定は NOT EXISTS サブクエリで 1 SQL に閉じ N+1 を回避する。
     * アイテムは作成日時の降順で {@code limit} 件に絞る。</p>
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID
     * @param userId    閲覧ユーザー ID
     * @param limit     直近アイテムの最大件数
     * @return 未回答の総件数と limit 件のアイテム
     */
    public UnansweredSurveys getUnansweredForUserInScope(
            String scopeType, Long scopeId, Long userId, int limit) {
        if (accessControlService != null) {
            // 配信＝受信権 統一: 未回答集計の入口は広め（includeSupporters=true）で通す。
            // 未回答クエリ findUnansweredPublishedForUserInScope は userId が回答対象のアンケのみ返すため、
            // 配信母集団外ユーザーは 0 件になり過小排除も漏洩も起きない。トグル ON 配信の配下 SUPPORTER も
            // 入口で弾かれないようにする（ScopeActionRequiredFacade と同方針）。
            accessControlService.checkMembershipOrDescendant(userId, scopeId, scopeType, true);
        }
        List<SurveyEntity> all =
                surveyRepository.findUnansweredPublishedForUserInScope(scopeType, scopeId, userId);
        List<SurveyEntity> items = all.size() > limit ? all.subList(0, limit) : all;
        return new UnansweredSurveys(all.size(), List.copyOf(items));
    }

    /**
     * F22.1 第二波: 未回答アンケートの集計結果（件数 + 直近アイテム）。
     *
     * @param unansweredCount 未回答の総件数
     * @param items           直近アイテム（limit 件）
     */
    public record UnansweredSurveys(
            long unansweredCount,
            List<SurveyEntity> items) {
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
        // 軍議③ F00 漏洩根治: 本体詳細もスコープ所属者のみ。非所属は存在露見前に COMMON_002(403) で弾く
        // （findSurveyOrThrow の前に置くことで、他スコープの surveyId 有無を漏らさない）。
        // DRAFT はメンバーに従来どおり見せる（status ガードは足さない）。手本: CirculationService.getDocument。
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembershipOrDescendant(
                currentUserId, scopeId, scopeType, true);
        SurveyEntity entity = findSurveyOrThrow(scopeType, scopeId, surveyId);
        return toDetailResponse(entity, currentUserId);
    }

    /**
     * エンティティ→詳細レスポンスへの純マッピング（認可ゲート無し）。
     *
     * <p>作成/複製（作成者自身・SecurityContext 不在のバッチ materialize 含む）と、
     * ガード付き HTTP GET 経路（{@link #getSurveyDetail}）が共用する。認可は呼び出し側で行う。</p>
     *
     * <p>Issue #2779: {@code viewerCanViewResults} は結果取得 API が 403 を投げるのと
     * 同じ判定点（{@link SurveyResultAccessPolicy}）から得る。作成者本人は高速パスで
     * 短絡されるため、作成/複製の経路では追加のクエリが発行されない。</p>
     *
     * @param entity 対象アンケート
     * @param userId 閲覧者ユーザーID（{@code null} 可 = SecurityContext 不在）
     */
    private SurveyDetailResponse toDetailResponse(SurveyEntity entity, Long userId) {
        SurveyResponse surveyResponse = surveyMapper.toSurveyResponse(entity);
        List<QuestionResponse> questions = buildQuestionResponses(entity.getId());
        boolean viewerCanViewResults = resultAccessPolicy.canViewResults(entity, userId);
        return SurveyDetailResponse.of(surveyResponse, questions, viewerCanViewResults);
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

        boolean teamBreakdownEnabled = Boolean.TRUE.equals(request.getTeamBreakdownEnabled());
        boolean isAnonymous = Boolean.TRUE.equals(request.getIsAnonymous());
        // 御裁可B（匿名保護）: 匿名アンケート × チーム別内訳トグル ON は併用禁止。
        // 回答者の所属チームを内訳に出すと匿名性が崩れるため、作成時に弾く（400・症状を隠さない）。
        if (isAnonymous && teamBreakdownEnabled) {
            throw new BusinessException(SurveyErrorCode.ANONYMOUS_TEAM_BREAKDOWN_CONFLICT);
        }

        String remindJson = serializeRemindHours(request.getRemindBeforeHours());

        // enum 項目は DTO が enum 型で受けるため（#2617-1）、未知値は Jackson の束縛段階で
        // 400 として弾かれ、ここに到達する時点で正当値であることが保証される。
        // Service 側での再パースは不要（二重の正本を作らない）。
        ResultsVisibility resultsVisibility = request.getResultsVisibility();
        DistributionMode distributionMode = request.getDistributionMode();
        UnrespondedVisibility unrespondedVisibility = request.getUnrespondedVisibility() != null
                ? request.getUnrespondedVisibility()
                : UnrespondedVisibility.CREATOR_AND_ADMIN;

        SurveyEntity entity = SurveyEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .title(request.getTitle())
                .description(request.getDescription())
                .isAnonymous(request.getIsAnonymous())
                .allowMultipleSubmissions(request.getAllowMultipleSubmissions())
                .resultsVisibility(resultsVisibility)
                .distributionMode(distributionMode)
                .unrespondedVisibility(unrespondedVisibility)
                .autoPostToTimeline(request.getAutoPostToTimeline() != null
                        ? request.getAutoPostToTimeline() : false)
                .includeSupporters(request.getIncludeSupporters() != null
                        ? request.getIncludeSupporters() : false)
                .teamBreakdownEnabled(teamBreakdownEnabled)
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

        // 掲示板スレッド自動作成イベントを発行（AFTER_COMMIT で非同期実行）
        eventPublisher.publishEvent(new SurveyCreatedEvent(saved.getId(), scopeType, scopeId, saved.getTitle()));

        // 作成直後の詳細は非ガードマッパで返す（作成者自身・SecurityContext 不在のバッチ経路でも安全）。
        return toDetailResponse(saved, userId);
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
                || request.getResultsVisibility() != null || request.getAutoPostToTimeline() != null
                || request.getIncludeSupporters() != null) {
            entity.updateSettings(
                    request.getIsAnonymous() != null ? request.getIsAnonymous() : entity.getIsAnonymous(),
                    request.getAllowMultipleSubmissions() != null
                            ? request.getAllowMultipleSubmissions() : entity.getAllowMultipleSubmissions(),
                    request.getResultsVisibility() != null
                            ? request.getResultsVisibility()
                            : entity.getResultsVisibility(),
                    request.getAutoPostToTimeline() != null
                            ? request.getAutoPostToTimeline() : entity.getAutoPostToTimeline(),
                    request.getIncludeSupporters() != null
                            ? request.getIncludeSupporters() : entity.getIncludeSupporters()
            );
        }
        if (request.getUnrespondedVisibility() != null) {
            entity.updateUnrespondedVisibility(request.getUnrespondedVisibility());
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

        // 公開時通知（F05.4 §1528 SURVEY_CREATED）を AFTER_COMMIT・非同期で発火する（規模対応 Tier2）。
        // 受信者ループ（通知行作成）は SurveyPublishNotificationListener が event-pool で実行し、
        // 公開 API 応答はここで即返しする。配信母集団の解決はリスナー側で行う
        // （組織×ALL は OrganizationMembershipService 経由で配下チームを展開）。
        eventPublisher.publishEvent(new SurveyPublishedEvent(
                saved.getId(),
                scopeType,
                scopeId,
                saved.getTitle(),
                saved.getDistributionMode(),
                Boolean.TRUE.equals(saved.getIncludeSupporters()),
                saved.getCreatedBy()));

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

        // 掲示板スレッドロックイベントを発行（AFTER_COMMIT で非同期実行）
        eventPublisher.publishEvent(new SurveyStatusChangedEvent(surveyId, SurveyStatus.CLOSED));

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
                .questionType(request.getQuestionType())
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
     * アンケート締切を延長する（F05.4 §4.7 extend）。
     *
     * <p>認可: 作成者 / ADMIN+。状態は PUBLISHED のみ受付。
     * 短縮は不可（既に期限内の回答者への約束を守るため）。
     * カウンタ・受信者への通知も送信する。</p>
     *
     * @param scopeType     スコープ種別
     * @param scopeId       スコープ ID
     * @param surveyId      対象アンケート ID
     * @param newDeadline   新しい締切（現在の {@code expires_at} より後）
     * @param currentUserId 操作実行者ユーザー ID
     * @return 延長後のアンケートレスポンス
     */
    @Transactional
    public SurveyResponse extendDeadline(String scopeType, Long scopeId, Long surveyId,
                                          java.time.LocalDateTime newDeadline, Long currentUserId) {
        SurveyEntity entity = findSurveyOrThrow(scopeType, scopeId, surveyId);

        // 認可: 作成者 or ADMIN+
        boolean isCreator = entity.getCreatedBy() != null && entity.getCreatedBy().equals(currentUserId);
        boolean isAdmin = accessControlService.isAdminOrAbove(currentUserId, scopeId, scopeType);
        if (!isCreator && !isAdmin) {
            throw new BusinessException(SurveyErrorCode.OPERATION_PERMISSION_DENIED);
        }

        // 状態: PUBLISHED のみ
        if (entity.getStatus() != SurveyStatus.PUBLISHED) {
            throw new BusinessException(SurveyErrorCode.INVALID_SURVEY_STATUS);
        }

        // 短縮不可: 新締切は現在の expires_at より後
        if (newDeadline == null) {
            throw new BusinessException(SurveyErrorCode.INVALID_NEW_DEADLINE);
        }
        if (entity.getExpiresAt() != null && !newDeadline.isAfter(entity.getExpiresAt())) {
            throw new BusinessException(SurveyErrorCode.INVALID_NEW_DEADLINE);
        }

        entity.updatePeriod(entity.getStartsAt(), newDeadline);
        SurveyEntity saved = surveyRepository.save(entity);

        // 受信者通知（distribution_mode に応じた母集団）
        // 組織×ALL は配下参加チームを展開する（OrganizationMembershipService 経由・越境是正）。
        // recipients は publish（SurveyPublishNotificationListener#resolveRecipients）・
        // remind（SurveyRemindService）と同一の配信母集団解決ロジック（ALL=resolveAllModeRecipients=
        // resolveOrgDistributionUserIds(includeSupporters トグル準拠)／TARGETED=survey_targets）を用いる。
        List<Long> recipients = saved.getDistributionMode() == DistributionMode.ALL
                ? resolveAllModeRecipients(scopeType, scopeId, saved)
                : targetRepository.findBySurveyId(surveyId).stream()
                    .map(SurveyTargetEntity::getUserId)
                    .distinct()
                    .toList();
        NotificationScopeType notifScope = "TEAM".equals(scopeType)
                ? NotificationScopeType.TEAM
                : NotificationScopeType.ORGANIZATION;
        // 配信＝受信権 統一（関所(1)通知 / E: ResultsVisibility 誤用是正）:
        // recipients は上記のとおり配信母集団として事前認可済みのため、publish/remind と同形に
        // notifyAllPreAuthorized を用いて canView 絞り込み（SURVEY の結果閲覧 ResultsVisibility 軸を含む）を
        // 通さない。これにより締切延長通知が直属一般メンバー・配下チームメンバーへ誤 deny で届かない
        // (B) レグを根治する。
        if (!recipients.isEmpty()) {
            notificationHelper.notifyAllPreAuthorized(
                    recipients,
                    SurveyNotificationType.SURVEY_RESPONSE_REMINDER.name(),
                    "アンケート締切が延長されました",
                    "「" + saved.getTitle() + "」の回答締切が " + newDeadline + " に延長されました。",
                    "SURVEY",
                    surveyId,
                    notifScope,
                    scopeId,
                    "/surveys/" + surveyId,
                    currentUserId);
        }

        log.info("アンケート締切延長: surveyId={}, newDeadline={}, by={}", surveyId, newDeadline, currentUserId);
        return surveyMapper.toSurveyResponse(saved);
    }

    /**
     * アンケートを複製する（F05.4 §4.6 duplicate）。
     *
     * <p>新規 {@code DRAFT} を作成し、設問・選択肢・配信対象・結果閲覧者をコピーする。
     * 回答データ・状態・日時はリセットする。タイトル末尾に「（コピー）」を付与する
     * （リクエストで {@code title} が指定された場合はそれを優先）。</p>
     *
     * @param scopeType     スコープ種別
     * @param scopeId       スコープ ID
     * @param surveyId      コピー元アンケート ID
     * @param request       複製リクエスト（タイトル・seriesId 任意）
     * @param currentUserId 作成者ユーザー ID
     * @return 複製後の新アンケート詳細
     */
    @Transactional
    public SurveyDetailResponse duplicateSurvey(String scopeType, Long scopeId, Long surveyId,
                                                 DuplicateSurveyRequest request, Long currentUserId) {
        SurveyEntity source = findSurveyOrThrow(scopeType, scopeId, surveyId);

        // 認可: 作成者 or ADMIN+
        boolean isCreator = source.getCreatedBy() != null && source.getCreatedBy().equals(currentUserId);
        boolean isAdmin = accessControlService.isAdminOrAbove(currentUserId, scopeId, scopeType);
        if (!isCreator && !isAdmin) {
            throw new BusinessException(SurveyErrorCode.OPERATION_PERMISSION_DENIED);
        }

        String newTitle = (request != null && request.getTitle() != null && !request.getTitle().isBlank())
                ? request.getTitle()
                : source.getTitle() + "（コピー）";
        String newSeriesId = (request != null && request.getSeriesId() != null)
                ? request.getSeriesId()
                : source.getSeriesId();

        SurveyEntity newEntity = SurveyEntity.builder()
                .scopeType(source.getScopeType())
                .scopeId(source.getScopeId())
                .title(newTitle)
                .description(source.getDescription())
                .status(SurveyStatus.DRAFT)
                .isAnonymous(source.getIsAnonymous())
                .allowMultipleSubmissions(source.getAllowMultipleSubmissions())
                .resultsVisibility(source.getResultsVisibility())
                .distributionMode(source.getDistributionMode())
                .unrespondedVisibility(source.getUnrespondedVisibility())
                .autoPostToTimeline(source.getAutoPostToTimeline())
                .seriesId(newSeriesId)
                .remindBeforeHours(source.getRemindBeforeHours())
                .createdBy(currentUserId)
                .build();
        SurveyEntity savedNew = surveyRepository.save(newEntity);

        // 設問・選択肢をコピー
        List<SurveyQuestionEntity> sourceQuestions =
                questionRepository.findBySurveyIdOrderByDisplayOrderAsc(surveyId);
        for (SurveyQuestionEntity sq : sourceQuestions) {
            SurveyQuestionEntity newQ = SurveyQuestionEntity.builder()
                    .surveyId(savedNew.getId())
                    .questionType(sq.getQuestionType())
                    .questionText(sq.getQuestionText())
                    .isRequired(sq.getIsRequired())
                    .displayOrder(sq.getDisplayOrder())
                    .maxSelections(sq.getMaxSelections())
                    .scaleMin(sq.getScaleMin())
                    .scaleMax(sq.getScaleMax())
                    .scaleMinLabel(sq.getScaleMinLabel())
                    .scaleMaxLabel(sq.getScaleMaxLabel())
                    .build();
            SurveyQuestionEntity savedQ = questionRepository.save(newQ);
            List<SurveyOptionEntity> sourceOptions =
                    optionRepository.findByQuestionIdOrderByDisplayOrderAsc(sq.getId());
            for (SurveyOptionEntity so : sourceOptions) {
                SurveyOptionEntity newOpt = SurveyOptionEntity.builder()
                        .questionId(savedQ.getId())
                        .optionText(so.getOptionText())
                        .displayOrder(so.getDisplayOrder())
                        .build();
                optionRepository.save(newOpt);
            }
        }

        // 配信対象 / 結果閲覧者をコピー
        List<SurveyTargetEntity> sourceTargets = targetRepository.findBySurveyId(surveyId);
        for (SurveyTargetEntity t : sourceTargets) {
            targetRepository.save(SurveyTargetEntity.builder()
                    .surveyId(savedNew.getId())
                    .userId(t.getUserId())
                    .build());
        }
        if (!sourceTargets.isEmpty()) {
            savedNew.updateTargetCount(sourceTargets.size());
            surveyRepository.save(savedNew);
        }

        List<SurveyResultViewerEntity> sourceViewers =
                resultViewerRepository.findBySurveyId(surveyId);
        for (SurveyResultViewerEntity v : sourceViewers) {
            resultViewerRepository.save(SurveyResultViewerEntity.builder()
                    .surveyId(savedNew.getId())
                    .userId(v.getUserId())
                    .build());
        }

        log.info("アンケート複製: source={}, new={}, by={}", surveyId, savedNew.getId(), currentUserId);
        // 複製直後の詳細も非ガードマッパで返す（getSurveyDetail のガードを経由しない）。
        return toDetailResponse(savedNew, currentUserId);
    }

    /**
     * アンケート統計を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return アンケート統計レスポンス
     */
    public SurveyStatsResponse getStats(String scopeType, Long scopeId) {
        // 軍議③ F00 漏洩根治: 集計もスコープ所属者のみ。非所属は COMMON_002(403)。
        // 件数集計から他スコープの本体規模（下書き数等）を推測される漏洩を防ぐ。
        // 手本: CirculationService.listDocuments の per-scope 所属ゲートと同型。
        accessControlService.checkMembershipOrDescendant(
                SecurityUtils.getCurrentUserId(), scopeId, scopeType, true);
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
     * {@code distribution_mode = ALL} の配信母集団を解決する。
     *
     * <p>組織スコープでは {@link OrganizationMembershipService#resolveOrgDistributionUserIds}
     * 経由で「直属 ∪ 配下ACTIVEチーム」を展開し、応援者トグル（{@code includeSupporters}）を
     * 適用する。チームスコープ（および COMMITTEE 等）は配下展開を行わず、従来どおり
     * {@link UserRoleRepository#findUserIdsByScope} でスコープ内メンバーを解決する。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID
     * @param survey    対象アンケート（応援者トグル参照のため）
     * @return 配信対象ユーザー ID リスト
     */
    private List<Long> resolveAllModeRecipients(String scopeType, Long scopeId, SurveyEntity survey) {
        if ("ORGANIZATION".equals(scopeType)) {
            return organizationMembershipService.resolveOrgDistributionUserIds(
                    scopeId, Boolean.TRUE.equals(survey.getIncludeSupporters()));
        }
        return userRoleRepository.findUserIdsByScope(scopeType, scopeId);
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
                    .questionType(qReq.getQuestionType())
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
     * enum 文字列フィールドを安全にパースする。
     *
     * <p>follow-up②: {@code Enum.valueOf(...)} は不正値で {@link IllegalArgumentException} を投げ、
     * GlobalExceptionHandler の汎用ハンドラに落ちて 500 COMMON_999 になる。本来はクライアント入力
     * エラーなので、ここで捕捉して {@link SurveyErrorCode#INVALID_ENUM_VALUE}（Severity.WARN → 400）
     * に変換する。握りつぶして既定値に倒す対処療法はしない（症状を隠さない）。</p>
     *
     * @param enumClass パース対象の enum 型
     * @param value     クライアント入力の文字列値
     * @param fieldName エラー応答に含めるフィールド名
     * @param <E>       enum 型
     * @return パース済み enum 値
     * @throws BusinessException 値が定義済み enum 値に一致しない場合（INVALID_ENUM_VALUE）
     */
    private <E extends Enum<E>> E parseEnumOrThrow(Class<E> enumClass, String value, String fieldName) {
        try {
            return Enum.valueOf(enumClass, value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                    SurveyErrorCode.INVALID_ENUM_VALUE,
                    List.of(new com.mannschaft.app.common.ErrorResponse.FieldError(
                            fieldName,
                            "指定された値は許可されていません: " + value)));
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
