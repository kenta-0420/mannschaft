package com.mannschaft.app.onboarding.event;

import com.mannschaft.app.onboarding.OnboardingCompletionType;
import com.mannschaft.app.onboarding.OnboardingProgressStatus;
import com.mannschaft.app.onboarding.OnboardingStepType;
import com.mannschaft.app.onboarding.entity.OnboardingProgressEntity;
import com.mannschaft.app.onboarding.entity.OnboardingTemplateStepEntity;
import com.mannschaft.app.onboarding.repository.OnboardingProgressRepository;
import com.mannschaft.app.onboarding.repository.OnboardingStepCompletionRepository;
import com.mannschaft.app.onboarding.repository.OnboardingTemplateStepRepository;
import com.mannschaft.app.onboarding.service.OnboardingProgressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * プロフィール更新時のオンボーディングステップ自動完了リスナー。
 * PROFILE_COMPLETION型ステップの自動完了を行う。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnboardingProfileCompletionListener {

    private final OnboardingProgressRepository progressRepository;
    private final OnboardingTemplateStepRepository stepRepository;
    private final OnboardingStepCompletionRepository stepCompletionRepository;
    private final OnboardingProgressService progressService;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleProfileUpdated(ProfileUpdatedEvent event) {
        log.info("プロフィール更新イベント受信: userId={}", event.getUserId());

        List<OnboardingProgressEntity> progressList = progressRepository
                .findByUserIdAndScopeTypeAndScopeIdAndStatus(
                        event.getUserId(), event.getScopeType(), event.getScopeId(),
                        OnboardingProgressStatus.IN_PROGRESS);

        for (OnboardingProgressEntity progress : progressList) {
            List<OnboardingTemplateStepEntity> profileSteps = stepRepository
                    .findByTemplateIdAndStepType(
                            progress.getTemplateId(), OnboardingStepType.PROFILE_COMPLETION);

            for (OnboardingTemplateStepEntity step : profileSteps) {
                boolean alreadyDone = stepCompletionRepository
                        .existsByProgressIdAndStepId(progress.getId(), step.getId());
                if (!alreadyDone) {
                    try {
                        progressService.completeStep(progress.getId(), step.getId(), OnboardingCompletionType.AUTO_PROFILE);
                        log.info("PROFILE型ステップ自動完了: progressId={}, stepId={}", progress.getId(), step.getId());
                    } catch (Exception e) {
                        log.warn("PROFILE型ステップ自動完了失敗: progressId={}, stepId={}, error={}",
                                progress.getId(), step.getId(), e.getMessage());
                    }
                }
            }
        }
    }
}
