package com.mannschaft.app.onboarding.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.onboarding.OnboardingCompletionType;
import com.mannschaft.app.onboarding.OnboardingErrorCode;
import com.mannschaft.app.onboarding.OnboardingMapper;
import com.mannschaft.app.onboarding.OnboardingProgressStatus;
import com.mannschaft.app.onboarding.OnboardingStepType;
import com.mannschaft.app.onboarding.OnboardingTemplateStatus;
import com.mannschaft.app.onboarding.dto.*;
import com.mannschaft.app.onboarding.entity.OnboardingProgressEntity;
import com.mannschaft.app.onboarding.entity.OnboardingStepCompletionEntity;
import com.mannschaft.app.onboarding.entity.OnboardingTemplateEntity;
import com.mannschaft.app.onboarding.entity.OnboardingTemplateStepEntity;
import com.mannschaft.app.onboarding.event.OnboardingCompletedEvent;
import com.mannschaft.app.onboarding.event.OnboardingStartedEvent;
import com.mannschaft.app.onboarding.repository.OnboardingProgressRepository;
import com.mannschaft.app.onboarding.repository.OnboardingStepCompletionRepository;
import com.mannschaft.app.onboarding.repository.OnboardingTemplateRepository;
import com.mannschaft.app.onboarding.repository.OnboardingTemplateStepRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * オンボーディング進捗管理サービス。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OnboardingProgressService {

    private final OnboardingProgressRepository progressRepository;
    private final OnboardingStepCompletionRepository stepCompletionRepository;
    private final OnboardingTemplateRepository templateRepository;
    private final OnboardingTemplateStepRepository stepRepository;
    private final OnboardingMapper mapper;
    private final DomainEventPublisher eventPublisher;
    private final NotificationHelper notificationHelper;
    private final org.springframework.context.MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;

    /**
     * オンボーディングを開始する。ACTIVEテンプレートで進捗を作成。
     */
    @Transactional
    public OnboardingProgressDetailResponse startOnboarding(Long userId, String scopeType, Long scopeId) {
        // ACTIVEテンプレート取得
        List<OnboardingTemplateEntity> activeTemplates = templateRepository
                .findByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, OnboardingTemplateStatus.ACTIVE);
        if (activeTemplates.isEmpty()) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_014);
        }
        OnboardingTemplateEntity template = activeTemplates.get(0);

        // 既存進捗チェック（同テンプレートで既にIN_PROGRESSがある場合はエラー）
        progressRepository.findByTemplateIdAndUserId(template.getId(), userId)
                .filter(p -> p.getStatus() == OnboardingProgressStatus.IN_PROGRESS)
                .ifPresent(p -> {
                    throw new BusinessException(OnboardingErrorCode.ONBOARDING_008);
                });

        List<OnboardingTemplateStepEntity> steps = stepRepository.findByTemplateIdOrderBySortOrder(template.getId());

        // 進捗作成
        OnboardingProgressEntity.OnboardingProgressEntityBuilder builder = OnboardingProgressEntity.builder()
                .userId(userId)
                .templateId(template.getId())
                .scopeType(scopeType)
                .scopeId(scopeId)
                .totalSteps((short) steps.size());

        if (template.getDeadlineDays() != null) {
            builder.deadlineAt(LocalDateTime.now().plusDays(template.getDeadlineDays()));
        }

        OnboardingProgressEntity progress = builder.build();
        OnboardingProgressEntity saved = progressRepository.save(progress);

        eventPublisher.publish(new OnboardingStartedEvent(
                saved.getId(), userId, scopeType, scopeId));

        log.info("オンボーディング開始: progressId={}, userId={}, templateId={}", saved.getId(), userId, template.getId());
        return buildProgressDetail(saved, template, steps, List.of());
    }

    /**
     * 進捗詳細を取得する（ステップ完了状況含む）。
     *
     * <p><b>認可は呼び出し元の責務</b>。{@code OnboardingProgressController}（ADMIN用）は
     * 呼び出し前に {@code verifyProgressAccess} でスコープ ADMIN を検証する。
     * メンバー本人向けの入口は {@link #getByIdForMember} を使うこと。</p>
     */
    public OnboardingProgressDetailResponse getById(Long progressId) {
        OnboardingProgressEntity progress = findProgressOrThrow(progressId);
        OnboardingTemplateEntity template = templateRepository.findById(progress.getTemplateId())
                .orElseThrow(() -> new BusinessException(OnboardingErrorCode.ONBOARDING_001));
        List<OnboardingTemplateStepEntity> steps = stepRepository.findByTemplateIdOrderBySortOrder(template.getId());
        List<OnboardingStepCompletionEntity> completions = stepCompletionRepository.findByProgressId(progressId);
        return buildProgressDetail(progress, template, steps, completions);
    }

    /**
     * メンバー本人向けに進捗詳細を取得する（本人所有チェック）。
     *
     * <p>認可根治戦役 Wave7: {@code progressId} から進捗エンティティを取得し、
     * 所有者が操作者本人であることを要求する。本人以外は
     * {@code ONBOARDING_003}（404・存在秘匿）とする。</p>
     */
    public OnboardingProgressDetailResponse getByIdForMember(Long progressId, Long userId) {
        OnboardingProgressEntity progress = findProgressOrThrow(progressId);
        if (!progress.getUserId().equals(userId)) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_003);
        }
        return getById(progressId);
    }

    /**
     * ADMIN用スコープ別進捗一覧を取得する。
     */
    public Page<OnboardingProgressResponse> listByScope(String scopeType, Long scopeId,
                                                         OnboardingProgressStatus status, Pageable pageable) {
        Page<OnboardingProgressEntity> page;
        if (status != null) {
            page = progressRepository.findByScopeTypeAndScopeIdAndStatus(
                    scopeType, scopeId, status, pageable);
        } else {
            page = progressRepository.findByScopeTypeAndScopeId(
                    scopeType, scopeId, pageable);
        }
        return page.map(this::toProgressResponse);
    }

    /**
     * メンバー用自分の進捗一覧を取得する。
     */
    public List<OnboardingProgressResponse> listByUser(Long userId, OnboardingProgressStatus status) {
        List<OnboardingProgressEntity> progressList;
        if (status != null) {
            progressList = progressRepository.findByUserIdAndStatus(userId, status);
        } else {
            progressList = progressRepository.findByUserId(userId);
        }
        return progressList.stream().map(this::toProgressResponse).toList();
    }

    /**
     * ステップを完了する。順序強制時は前ステップ検証。全完了時はcompleteOnboarding。
     */
    @Transactional
    public StepCompletionResponse completeStep(Long progressId, Long stepId, OnboardingCompletionType completionType) {
        OnboardingProgressEntity progress = findProgressOrThrow(progressId);

        if (progress.getStatus() != OnboardingProgressStatus.IN_PROGRESS) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_008);
        }

        OnboardingTemplateEntity template = templateRepository.findById(progress.getTemplateId())
                .orElseThrow(() -> new BusinessException(OnboardingErrorCode.ONBOARDING_001));
        OnboardingTemplateStepEntity step = stepRepository.findById(stepId)
                .orElseThrow(() -> new BusinessException(OnboardingErrorCode.ONBOARDING_002));

        // 既に完了済みチェック
        boolean alreadyCompleted = stepCompletionRepository.existsByProgressIdAndStepId(progressId, stepId);
        if (alreadyCompleted) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_009);
        }

        // 順序強制チェック
        if (Boolean.TRUE.equals(template.getIsOrderEnforced())) {
            validateStepOrder(progressId, step);
        }

        // ステップ完了記録作成
        OnboardingStepCompletionEntity completion = OnboardingStepCompletionEntity.builder()
                .progressId(progressId)
                .stepId(stepId)
                .completionType(completionType)
                .build();
        stepCompletionRepository.save(completion);

        // 進捗更新
        progress.incrementCompletedSteps();
        progressRepository.save(progress);

        int completedSteps = progress.getCompletedSteps();

        // 全ステップ完了チェック
        if (completedSteps >= progress.getTotalSteps()) {
            completeOnboarding(progress);
        }

        BigDecimal completionRate = calcCompletionRate(completedSteps, progress.getTotalSteps());

        log.info("オンボーディングステップ完了: progressId={}, stepId={}, completed={}/{}",
                progressId, stepId, completedSteps, progress.getTotalSteps());

        return new StepCompletionResponse(
                stepId, true, completion.getCompletedAt(), completionType,
                progress.getStatus(), (int) completedSteps, (int) progress.getTotalSteps(), completionRate);
    }

    /**
     * メンバー自身の手動完了（MANUAL/URLステップのみ）。
     *
     * <p>認可根治戦役 Wave7: {@link #getByIdForMember} と同一の本人チェックを先に適用し、
     * 進捗の所有者が操作者本人であることを要求する。他人の進捗は
     * {@code ONBOARDING_003}（404・存在秘匿）とする。</p>
     */
    @Transactional
    public StepCompletionResponse completeStepByMember(Long progressId, Long stepId, Long userId) {
        OnboardingProgressEntity progress = findProgressOrThrow(progressId);
        if (!progress.getUserId().equals(userId)) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_003);
        }

        OnboardingTemplateStepEntity step = stepRepository.findById(stepId)
                .orElseThrow(() -> new BusinessException(OnboardingErrorCode.ONBOARDING_002));

        // MANUAL/URLのみメンバー自身で完了可
        if (step.getStepType() != OnboardingStepType.MANUAL && step.getStepType() != OnboardingStepType.URL) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_011);
        }

        return completeStep(progressId, stepId, OnboardingCompletionType.MANUAL);
    }

    /**
     * ADMIN手動完了（ADMIN_OVERRIDE）。
     */
    @Transactional
    public StepCompletionResponse adminCompleteStep(Long progressId, Long stepId) {
        return completeStep(progressId, stepId, OnboardingCompletionType.ADMIN_OVERRIDE);
    }

    /**
     * オンボーディングをスキップする（IN_PROGRESS→SKIPPED）。
     */
    @Transactional
    public SkipProgressResponse skip(Long progressId) {
        OnboardingProgressEntity progress = findProgressOrThrow(progressId);

        if (progress.getStatus() != OnboardingProgressStatus.IN_PROGRESS) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_008);
        }

        progress.markSkipped();
        progressRepository.save(progress);

        log.info("オンボーディングスキップ: progressId={}", progressId);
        return new SkipProgressResponse(progress.getId(), progress.getStatus(), progress.getCompletedAt());
    }

    /**
     * オンボーディングをリセットする（ステップ完了記録全削除、IN_PROGRESSに戻す）。
     */
    @Transactional
    public ResetProgressResponse reset(Long progressId) {
        OnboardingProgressEntity progress = findProgressOrThrow(progressId);

        // ステップ完了記録を削除
        stepCompletionRepository.deleteByProgressId(progressId);

        // 進捗リセット
        progress.reset();
        progressRepository.save(progress);

        log.info("オンボーディングリセット: progressId={}", progressId);
        return new ResetProgressResponse(progress.getId(), progress.getStatus(), 0,
                (int) progress.getTotalSteps(), BigDecimal.ZERO);
    }

    /**
     * 手動一括リマインダーを送信する。
     */
    @Transactional
    // TODO: OnboardingドメインとNotificationドメインをまたいでいる。将来はOnboardingReminderRequestedEventで分離予定
    public RemindResponse sendReminders(String scopeType, Long scopeId) {
        List<OnboardingProgressEntity> inProgress = progressRepository
                .findByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, OnboardingProgressStatus.IN_PROGRESS);

        // Issue #2715 CMP-055 ロットC-5: 受信者ごとに locale が異なるため、ループの外で
        // UserLocaleCache#getLocales により一括解決する（N+1 防止）。
        Map<Long, String> locales = userLocaleCache.getLocales(
                inProgress.stream().map(OnboardingProgressEntity::getUserId).toList());

        int remindedCount = 0;
        for (OnboardingProgressEntity progress : inProgress) {
            try {
                NotificationScopeType notifScope = "TEAM".equals(scopeType) ?
                        NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;
                java.util.Locale locale = java.util.Locale.forLanguageTag(
                        locales.getOrDefault(progress.getUserId(), "ja"));
                notificationHelper.notify(
                        progress.getUserId(), "ONBOARDING_REMINDER",
                        messageSource.getMessage(
                                "notification.onboarding.reminder.title", null,
                                "オンボーディングリマインド", locale),
                        messageSource.getMessage(
                                "notification.onboarding.reminder.body", null,
                                "未完了のオンボーディングステップがあります。", locale),
                        "ONBOARDING", progress.getId(), notifScope, scopeId,
                        "/onboarding/progress/" + progress.getId(), null);
                remindedCount++;
            } catch (Exception e) {
                log.warn("リマインダー送信失敗: progressId={}, error={}", progress.getId(), e.getMessage());
            }
        }

        log.info("オンボーディングリマインダー送信: scope={}/{}, reminded={}/{}",
                scopeType, scopeId, remindedCount, inProgress.size());
        return new RemindResponse(remindedCount, inProgress.size());
    }

    // ========================================
    // 内部メソッド
    // ========================================

    private void completeOnboarding(OnboardingProgressEntity progress) {
        progress.markCompleted();
        progressRepository.save(progress);

        eventPublisher.publish(new OnboardingCompletedEvent(
                progress.getId(), progress.getUserId(), progress.getScopeType(), progress.getScopeId()));

        log.info("オンボーディング完了: progressId={}, userId={}", progress.getId(), progress.getUserId());
    }

    private void validateStepOrder(Long progressId, OnboardingTemplateStepEntity currentStep) {
        List<OnboardingTemplateStepEntity> allSteps = stepRepository
                .findByTemplateIdOrderBySortOrder(currentStep.getTemplateId());
        Set<Long> completedStepIds = stepCompletionRepository.findByProgressId(progressId)
                .stream()
                .map(OnboardingStepCompletionEntity::getStepId)
                .collect(Collectors.toSet());

        for (OnboardingTemplateStepEntity step : allSteps) {
            if (step.getSortOrder() < currentStep.getSortOrder() && !completedStepIds.contains(step.getId())) {
                throw new BusinessException(OnboardingErrorCode.ONBOARDING_010);
            }
        }
    }

    private OnboardingProgressEntity findProgressOrThrow(Long progressId) {
        return progressRepository.findById(progressId)
                .orElseThrow(() -> new BusinessException(OnboardingErrorCode.ONBOARDING_003));
    }

    private OnboardingProgressResponse toProgressResponse(OnboardingProgressEntity progress) {
        OnboardingTemplateEntity template = templateRepository.findById(progress.getTemplateId()).orElse(null);
        String templateName = template != null ? template.getName() : null;
        BigDecimal completionRate = calcCompletionRate(progress.getCompletedSteps(), progress.getTotalSteps());

        // UserSummaryは後続で拡張（現段階ではIDのみ）
        UserSummary userSummary = new UserSummary(progress.getUserId(), null, null);

        return new OnboardingProgressResponse(
                progress.getId(), userSummary, templateName, progress.getStatus(),
                (int) progress.getTotalSteps(), (int) progress.getCompletedSteps(), completionRate,
                progress.getDeadlineAt(), progress.getStartedAt(), progress.getCompletedAt());
    }

    private OnboardingProgressDetailResponse buildProgressDetail(
            OnboardingProgressEntity progress, OnboardingTemplateEntity template,
            List<OnboardingTemplateStepEntity> steps, List<OnboardingStepCompletionEntity> completions) {

        Map<Long, OnboardingStepCompletionEntity> completionMap = completions.stream()
                .collect(Collectors.toMap(OnboardingStepCompletionEntity::getStepId, Function.identity(), (a, b) -> a));

        List<StepProgressResponse> stepResponses = steps.stream()
                .map(step -> {
                    OnboardingStepCompletionEntity completion = completionMap.get(step.getId());
                    LocalDateTime stepDeadline = null;
                    if (step.getDeadlineOffsetDays() != null && progress.getStartedAt() != null) {
                        stepDeadline = progress.getStartedAt().plusDays(step.getDeadlineOffsetDays());
                    }
                    return new StepProgressResponse(
                            step.getId(), step.getTitle(), step.getDescription(),
                            step.getStepType(), step.getReferenceId(), step.getReferenceUrl(),
                            completion != null,
                            completion != null ? completion.getCompletedAt() : null,
                            completion != null ? completion.getCompletionType() : null,
                            stepDeadline);
                })
                .toList();

        BigDecimal completionRate = calcCompletionRate(progress.getCompletedSteps(), progress.getTotalSteps());

        return new OnboardingProgressDetailResponse(
                progress.getId(), progress.getScopeType(), progress.getScopeId(),
                null, // scopeName: 後続で拡張
                template.getName(), template.getWelcomeMessage(),
                progress.getStatus(), (int) progress.getTotalSteps(), (int) progress.getCompletedSteps(),
                completionRate, progress.getDeadlineAt(), progress.getStartedAt(), stepResponses);
    }

    private BigDecimal calcCompletionRate(int completed, int total) {
        if (total == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(completed)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
    }
}
