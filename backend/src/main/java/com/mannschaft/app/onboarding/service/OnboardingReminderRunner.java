package com.mannschaft.app.onboarding.service;

import com.mannschaft.app.onboarding.OnboardingProgressStatus;
import com.mannschaft.app.onboarding.entity.OnboardingProgressEntity;
import com.mannschaft.app.onboarding.entity.OnboardingTemplateEntity;
import com.mannschaft.app.onboarding.event.OnboardingReminderNotificationEvent;
import com.mannschaft.app.onboarding.repository.OnboardingProgressRepository;
import com.mannschaft.app.onboarding.repository.OnboardingTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * オンボーディング日次リマインドの「1 進捗ぶん」を実行する {@link Propagation#REQUIRES_NEW} 実行 Bean
 * （Issue #2834 / CMP-056 第2群ロット2。金型: {@code QuickMemoReminderRunner}・CMP-035）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code OnboardingReminderBatchService#processReminders} に {@code @Transactional} が付き、
 * 期限超過・期限前の全進捗を 1 トランザクションで包んだまま進捗単位に catch していた。
 * 1 件の DB 例外が rollback-only を残すため、catch して続行した<b>他の進捗の
 * {@code last_reminded_at} もコミット時にまとめて巻き戻り</b>、当日中の重複防止が効かなくなっていた。</p>
 *
 * <h2>再実行安全性（冪等）</h2>
 * <p>抽出時点のスナップショットを信じず、独立トランザクション内で進捗を読み直して
 * 「まだ {@code IN_PROGRESS} か」「今日まだリマインドしていないか」「期限条件を満たすか」を
 * <b>再判定</b>してから記録する。同日中の再実行で二度送ることはない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnboardingReminderRunner {

    private final OnboardingProgressRepository progressRepository;
    private final OnboardingTemplateRepository templateRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 1 進捗ぶんの日次リマインドを独立トランザクションで確定し、通知配送要求を publish する。
     *
     * <p>publish した通知配送要求は {@code AFTER_COMMIT} でのみ発火するため、
     * このトランザクションがロールバックすれば通知は作られない。</p>
     *
     * @param progressId 進捗ID
     * @param kind       リマインド種別（{@link OnboardingReminderNotificationEvent.Kind#OVERDUE} /
     *                   {@link OnboardingReminderNotificationEvent.Kind#DEADLINE_APPROACHING}）
     * @param now        判定基準時刻（JST）
     * @return 通知配送要求を publish した場合は {@code true}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean remindOne(Long progressId, OnboardingReminderNotificationEvent.Kind kind, LocalDateTime now) {
        // 抽出後に完了・スキップ・他プロセスのリマインドが起きている可能性があるため読み直す（冪等性の要）。
        OnboardingProgressEntity progress = progressRepository.findById(progressId).orElse(null);
        if (progress == null) {
            log.warn("オンボーディング進捗が読み直し時点で存在しません（スキップ）: progressId={}", progressId);
            return false;
        }
        if (progress.getStatus() != OnboardingProgressStatus.IN_PROGRESS) {
            return false;
        }
        if (progress.getLastRemindedAt() != null
                && progress.getLastRemindedAt().toLocalDate().equals(now.toLocalDate())) {
            // 今日は送信済み。二重送信しない。
            return false;
        }
        if (progress.getDeadlineAt() == null) {
            return false;
        }

        if (kind == OnboardingReminderNotificationEvent.Kind.OVERDUE) {
            if (!progress.getDeadlineAt().isBefore(now)) {
                // 抽出後に期限が延長された。超過通知は出さない。
                return false;
            }
        } else {
            if (progress.getDeadlineAt().isBefore(now)) {
                // 既に超過している。期限前リマインドではなく超過通知の対象なので、ここでは出さない。
                return false;
            }
            OnboardingTemplateEntity template =
                    templateRepository.findById(progress.getTemplateId()).orElse(null);
            if (template == null || template.getReminderDaysBefore() == null) {
                return false;
            }
            if (now.isBefore(progress.getDeadlineAt().minusDays(template.getReminderDaysBefore()))) {
                return false;
            }
        }

        progress.updateLastRemindedAt();
        progressRepository.save(progress);

        eventPublisher.publishEvent(new OnboardingReminderNotificationEvent(
                kind, progress.getScopeType(), progress.getScopeId(),
                List.of(new OnboardingReminderNotificationEvent.Recipient(
                        progress.getUserId(), progress.getId()))));
        return true;
    }
}
