package com.mannschaft.app.onboarding.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.onboarding.OnboardingErrorCode;
import com.mannschaft.app.onboarding.OnboardingMapper;
import com.mannschaft.app.onboarding.OnboardingProgressStatus;
import com.mannschaft.app.onboarding.OnboardingTemplateStatus;
import com.mannschaft.app.onboarding.dto.*;
import com.mannschaft.app.onboarding.entity.OnboardingTemplateEntity;
import com.mannschaft.app.onboarding.entity.OnboardingTemplateStepEntity;
import com.mannschaft.app.onboarding.entity.SystemOnboardingPresetEntity;
import com.mannschaft.app.onboarding.event.OnboardingTemplateActivatedEvent;
import com.mannschaft.app.onboarding.repository.OnboardingProgressRepository;
import com.mannschaft.app.onboarding.repository.OnboardingTemplateRepository;
import com.mannschaft.app.onboarding.repository.OnboardingTemplateStepRepository;
import com.mannschaft.app.onboarding.repository.SystemOnboardingPresetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * オンボーディングテンプレート管理サービス。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OnboardingTemplateService {

    private final OnboardingTemplateRepository templateRepository;
    private final OnboardingTemplateStepRepository stepRepository;
    private final OnboardingProgressRepository progressRepository;
    private final SystemOnboardingPresetRepository presetRepository;
    private final OnboardingMapper mapper;
    private final DomainEventPublisher eventPublisher;

    /**
     * テンプレートを作成する。presetId指定時はプリセットからコピー。
     */
    @Transactional
    public OnboardingTemplateResponse create(String scopeType, Long scopeId, Long userId,
                                              CreateOnboardingTemplateRequest request) {
        OnboardingTemplateEntity.OnboardingTemplateEntityBuilder builder = OnboardingTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .createdBy(userId);

        // プリセットからコピー or リクエスト値
        if (request.presetId() != null) {
            SystemOnboardingPresetEntity preset = presetRepository.findById(request.presetId())
                    .orElseThrow(() -> new BusinessException(OnboardingErrorCode.ONBOARDING_012));
            builder.name(request.name() != null ? request.name() : preset.getName())
                    .description(request.description() != null ? request.description() : preset.getDescription())
                    .welcomeMessage(request.welcomeMessage() != null ? request.welcomeMessage() : preset.getWelcomeMessage())
                    .isOrderEnforced(request.isOrderEnforced() != null ? request.isOrderEnforced() : preset.getIsOrderEnforced())
                    .deadlineDays(request.deadlineDays() != null ? request.deadlineDays().shortValue() : preset.getDeadlineDays())
                    .presetId(request.presetId());
        } else {
            builder.name(request.name())
                    .description(request.description())
                    .welcomeMessage(request.welcomeMessage())
                    .isOrderEnforced(request.isOrderEnforced() != null ? request.isOrderEnforced() : false)
                    .deadlineDays(request.deadlineDays() != null ? request.deadlineDays().shortValue() : null);
        }
        builder.reminderDaysBefore(request.reminderDaysBefore() != null ? request.reminderDaysBefore().shortValue() : null)
                .isAdminNotifiedOnComplete(request.isAdminNotifiedOnComplete() != null ? request.isAdminNotifiedOnComplete() : false)
                .isTimelinePostedOnComplete(request.isTimelinePostedOnComplete() != null ? request.isTimelinePostedOnComplete() : false);

        OnboardingTemplateEntity template = builder.build();
        OnboardingTemplateEntity saved = templateRepository.save(template);

        // ステップ作成
        List<OnboardingTemplateStepEntity> steps = createSteps(saved.getId(), request.steps());

        log.info("オンボーディングテンプレート作成: id={}, scope={}/{}", saved.getId(), scopeType, scopeId);
        return mapper.toTemplateResponse(saved, mapper.toStepResponseList(steps));
    }

    /**
     * テンプレート詳細を取得する（ステップ含む）。
     */
    public OnboardingTemplateResponse getById(Long templateId) {
        OnboardingTemplateEntity template = findTemplateOrThrow(templateId);
        List<OnboardingTemplateStepEntity> steps = stepRepository.findByTemplateIdOrderBySortOrder(templateId);
        return mapper.toTemplateResponse(template, mapper.toStepResponseList(steps));
    }

    /**
     * スコープ別テンプレート一覧を取得する。
     */
    public List<OnboardingTemplateResponse> listByScope(String scopeType, Long scopeId) {
        List<OnboardingTemplateEntity> templates = templateRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId);
        return templates.stream()
                .map(t -> {
                    List<OnboardingTemplateStepEntity> steps = stepRepository.findByTemplateIdOrderBySortOrder(t.getId());
                    return mapper.toTemplateResponse(t, mapper.toStepResponseList(steps));
                })
                .toList();
    }

    /**
     * テンプレートを更新する（DRAFT状態のみ）。ステップは全置換。
     */
    @Transactional
    public OnboardingTemplateResponse update(Long templateId, UpdateOnboardingTemplateRequest request) {
        OnboardingTemplateEntity template = findTemplateOrThrow(templateId);

        if (template.getStatus() != OnboardingTemplateStatus.DRAFT) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_004);
        }

        template.updateDraft(
                request.name(),
                request.description(),
                request.welcomeMessage(),
                request.isOrderEnforced() != null ? request.isOrderEnforced() : template.getIsOrderEnforced(),
                request.deadlineDays() != null ? request.deadlineDays().shortValue() : template.getDeadlineDays(),
                request.reminderDaysBefore() != null ? request.reminderDaysBefore().shortValue() : template.getReminderDaysBefore(),
                request.isAdminNotifiedOnComplete() != null ? request.isAdminNotifiedOnComplete() : template.getIsAdminNotifiedOnComplete(),
                request.isTimelinePostedOnComplete() != null ? request.isTimelinePostedOnComplete() : template.getIsTimelinePostedOnComplete()
        );

        OnboardingTemplateEntity saved = templateRepository.save(template);

        // ステップ全置換: 既存を削除→新規作成
        stepRepository.deleteByTemplateId(templateId);

        List<OnboardingTemplateStepEntity> newSteps = createSteps(templateId, request.steps());

        log.info("オンボーディングテンプレート更新: id={}", templateId);
        return mapper.toTemplateResponse(saved, mapper.toStepResponseList(newSteps));
    }

    /**
     * テンプレートをアクティベートする（DRAFT→ACTIVE）。既存ACTIVEを自動ARCHIVED。
     */
    @Transactional
    public ActivateTemplateResponse activate(Long templateId) {
        OnboardingTemplateEntity template = findTemplateOrThrow(templateId);

        if (template.getStatus() != OnboardingTemplateStatus.DRAFT) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_004);
        }

        // 既存ACTIVEをARCHIVED
        Long previousActiveId = null;
        List<OnboardingTemplateEntity> activeTemplates = templateRepository
                .findByScopeTypeAndScopeIdAndStatus(
                        template.getScopeType(), template.getScopeId(), OnboardingTemplateStatus.ACTIVE);
        for (OnboardingTemplateEntity active : activeTemplates) {
            active.archive();
            templateRepository.save(active);
            previousActiveId = active.getId();
        }

        // DRAFT→ACTIVE
        template.activate();
        OnboardingTemplateEntity saved = templateRepository.save(template);

        eventPublisher.publish(new OnboardingTemplateActivatedEvent(
                saved.getId(), saved.getScopeType(), saved.getScopeId()));

        log.info("オンボーディングテンプレートアクティベート: id={}, previousActive={}", templateId, previousActiveId);
        return new ActivateTemplateResponse(saved.getId(), saved.getStatus(), previousActiveId, saved.getVersion());
    }

    /**
     * テンプレートをアーカイブする（ACTIVE→ARCHIVED）。
     */
    @Transactional
    public OnboardingTemplateResponse archive(Long templateId) {
        OnboardingTemplateEntity template = findTemplateOrThrow(templateId);

        if (template.getStatus() != OnboardingTemplateStatus.ACTIVE) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_005);
        }

        template.archive();
        OnboardingTemplateEntity saved = templateRepository.save(template);
        List<OnboardingTemplateStepEntity> steps = stepRepository.findByTemplateIdOrderBySortOrder(templateId);
        log.info("オンボーディングテンプレートアーカイブ: id={}", templateId);
        return mapper.toTemplateResponse(saved, mapper.toStepResponseList(steps));
    }

    /**
     * テンプレートを論理削除する。条件: DRAFT or (ARCHIVED かつ進行中進捗なし)。
     */
    @Transactional
    public void delete(Long templateId) {
        OnboardingTemplateEntity template = findTemplateOrThrow(templateId);

        if (template.getStatus() == OnboardingTemplateStatus.ACTIVE) {
            throw new BusinessException(OnboardingErrorCode.ONBOARDING_005);
        }

        if (template.getStatus() == OnboardingTemplateStatus.ARCHIVED) {
            long inProgressCount = progressRepository.countByTemplateIdAndStatus(
                    templateId, OnboardingProgressStatus.IN_PROGRESS);
            if (inProgressCount > 0) {
                throw new BusinessException(OnboardingErrorCode.ONBOARDING_015);
            }
        }

        template.softDelete();
        templateRepository.save(template);

        // ステップも削除
        stepRepository.deleteByTemplateId(templateId);

        log.info("オンボーディングテンプレート削除: id={}", templateId);
    }

    /**
     * テンプレートを複製する（DRAFT状態で作成）。
     */
    @Transactional
    public OnboardingTemplateResponse duplicate(Long templateId) {
        OnboardingTemplateEntity original = findTemplateOrThrow(templateId);
        List<OnboardingTemplateStepEntity> originalSteps = stepRepository.findByTemplateIdOrderBySortOrder(templateId);

        OnboardingTemplateEntity duplicate = OnboardingTemplateEntity.builder()
                .scopeType(original.getScopeType())
                .scopeId(original.getScopeId())
                .createdBy(original.getCreatedBy())
                .name(original.getName() + " (コピー)")
                .description(original.getDescription())
                .welcomeMessage(original.getWelcomeMessage())
                .isOrderEnforced(original.getIsOrderEnforced())
                .deadlineDays(original.getDeadlineDays())
                .reminderDaysBefore(original.getReminderDaysBefore())
                .isAdminNotifiedOnComplete(original.getIsAdminNotifiedOnComplete())
                .isTimelinePostedOnComplete(original.getIsTimelinePostedOnComplete())
                .build();

        OnboardingTemplateEntity saved = templateRepository.save(duplicate);

        // ステップ複製
        List<OnboardingTemplateStepEntity> duplicatedSteps = originalSteps.stream()
                .map(s -> OnboardingTemplateStepEntity.builder()
                        .templateId(saved.getId())
                        .title(s.getTitle())
                        .description(s.getDescription())
                        .stepType(s.getStepType())
                        .referenceId(s.getReferenceId())
                        .referenceUrl(s.getReferenceUrl())
                        .deadlineOffsetDays(s.getDeadlineOffsetDays())
                        .sortOrder(s.getSortOrder())
                        .build())
                .toList();
        List<OnboardingTemplateStepEntity> savedSteps = stepRepository.saveAll(duplicatedSteps);

        log.info("オンボーディングテンプレート複製: originalId={}, newId={}", templateId, saved.getId());
        return mapper.toTemplateResponse(saved, mapper.toStepResponseList(savedSteps));
    }

    // ========================================
    // 内部メソッド
    // ========================================

    private OnboardingTemplateEntity findTemplateOrThrow(Long templateId) {
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(OnboardingErrorCode.ONBOARDING_001));
    }

    private List<OnboardingTemplateStepEntity> createSteps(Long templateId, List<CreateStepRequest> stepRequests) {
        if (stepRequests == null || stepRequests.isEmpty()) {
            return List.of();
        }
        List<OnboardingTemplateStepEntity> steps = stepRequests.stream()
                .map(r -> {
                    OnboardingTemplateStepEntity step = mapper.toStepEntity(r);
                    return step.toBuilder().templateId(templateId).build();
                })
                .toList();
        return stepRepository.saveAll(steps);
    }
}
