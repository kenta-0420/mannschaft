package com.mannschaft.app.onboarding.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.onboarding.OnboardingProgressStatus;
import com.mannschaft.app.onboarding.OnboardingTemplateStatus;
import com.mannschaft.app.onboarding.entity.OnboardingProgressEntity;
import com.mannschaft.app.onboarding.entity.OnboardingTemplateEntity;
import com.mannschaft.app.onboarding.repository.OnboardingProgressRepository;
import com.mannschaft.app.onboarding.repository.OnboardingTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * オンボーディングリマインダーバッチサービス。
 * 期限前リマインダーおよび期限超過通知を毎日送信する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OnboardingReminderBatchService {

    private final OnboardingProgressRepository progressRepository;
    private final OnboardingTemplateRepository templateRepository;
    private final NotificationHelper notificationHelper;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;

    /**
     * 毎日9時（JST）に実行。期限前リマインダーと期限超過通知を送信する。
     */
    @BatchEndpoint(name = "onboarding-reminder-daily", description = "オンボーディング期限前リマインドと超過通知を毎日 09:00 に送信する")
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "onboardingReminderBatch", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    @Transactional
    // TODO: OnboardingドメインとNotificationドメインをまたいでいる。将来はOnboardingOverdueNotificationEventで分離予定
    public void processReminders() {
        log.info("オンボーディングリマインダーバッチ開始");
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Tokyo"));
        LocalDate today = now.toLocalDate();
        int reminderCount = 0;
        int overdueCount = 0;

        // 期限超過通知: deadline_at < now かつ IN_PROGRESS
        List<OnboardingProgressEntity> overdue = progressRepository
                .findByStatusAndDeadlineAtBefore(OnboardingProgressStatus.IN_PROGRESS, now);

        // Issue #2715 CMP-055 ロットC-5: 受信者ごとに locale が異なるため、ループの外で一括解決する（N+1 防止）。
        Map<Long, String> overdueLocales = userLocaleCache.getLocales(
                overdue.stream().map(OnboardingProgressEntity::getUserId).toList());

        for (OnboardingProgressEntity progress : overdue) {
            if (isAlreadyRemindedToday(progress, today)) {
                continue;
            }
            try {
                NotificationScopeType notifScope = resolveNotifScope(progress);
                Locale locale = Locale.forLanguageTag(
                        overdueLocales.getOrDefault(progress.getUserId(), "ja"));
                notificationHelper.notify(
                        progress.getUserId(), "ONBOARDING_OVERDUE",
                        messageSource.getMessage(
                                "notification.onboarding.overdue.title", null,
                                "オンボーディング期限超過", locale),
                        messageSource.getMessage(
                                "notification.onboarding.overdue.body", null,
                                "オンボーディングの期限が過ぎています。早めに完了してください。", locale),
                        "ONBOARDING", progress.getId(), notifScope, progress.getScopeId(),
                        "/onboarding/progress/" + progress.getId(), null);
                progress.updateLastRemindedAt();
                overdueCount++;
            } catch (Exception e) {
                log.warn("期限超過通知送信失敗: progressId={}", progress.getId(), e);
            }
        }

        // 期限前リマインダー: テンプレートの reminder_days_before を参照
        List<OnboardingProgressEntity> inProgress = progressRepository
                .findByStatusAndDeadlineAtBetween(OnboardingProgressStatus.IN_PROGRESS, now, now.plusDays(30));

        Map<Long, String> reminderLocales = userLocaleCache.getLocales(
                inProgress.stream().map(OnboardingProgressEntity::getUserId).toList());

        for (OnboardingProgressEntity progress : inProgress) {
            if (isAlreadyRemindedToday(progress, today)) {
                continue;
            }
            // テンプレートの reminder_days_before を取得してリマインダー対象か判定
            OnboardingTemplateEntity template = templateRepository.findById(progress.getTemplateId()).orElse(null);
            if (template == null || template.getReminderDaysBefore() == null) {
                continue;
            }
            LocalDateTime reminderThreshold = progress.getDeadlineAt()
                    .minusDays(template.getReminderDaysBefore());
            if (now.isBefore(reminderThreshold)) {
                continue;
            }
            try {
                NotificationScopeType notifScope = resolveNotifScope(progress);
                Locale locale = Locale.forLanguageTag(
                        reminderLocales.getOrDefault(progress.getUserId(), "ja"));
                // AC-7: 期限日の表記もロケール化する（値そのものは変えない）。
                String deadlinePattern = messageSource.getMessage(
                        "notification.onboarding.reminder.deadlinePattern", null,
                        "yyyy年M月d日", locale);
                String formattedDeadline = progress.getDeadlineAt().toLocalDate()
                        .format(DateTimeFormatter.ofPattern(deadlinePattern, locale));
                notificationHelper.notify(
                        progress.getUserId(), "ONBOARDING_REMINDER",
                        messageSource.getMessage(
                                "notification.onboarding.reminder.title", null,
                                "オンボーディングリマインド", locale),
                        messageSource.getMessage(
                                "notification.onboarding.reminder.deadlineBody",
                                new Object[]{formattedDeadline},
                                "オンボーディングの期限が近づいています（期限: " + formattedDeadline + "）。",
                                locale),
                        "ONBOARDING", progress.getId(), notifScope, progress.getScopeId(),
                        "/onboarding/progress/" + progress.getId(), null);
                progress.updateLastRemindedAt();
                reminderCount++;
            } catch (Exception e) {
                log.warn("リマインダー送信失敗: progressId={}", progress.getId(), e);
            }
        }

        log.info("オンボーディングリマインダーバッチ完了: reminder={}, overdue={}", reminderCount, overdueCount);
    }

    /**
     * 今日既にリマインダーを送信済みかチェック（重複防止）。
     */
    private boolean isAlreadyRemindedToday(OnboardingProgressEntity progress, LocalDate today) {
        return progress.getLastRemindedAt() != null
                && progress.getLastRemindedAt().toLocalDate().equals(today);
    }

    private NotificationScopeType resolveNotifScope(OnboardingProgressEntity progress) {
        return "TEAM".equals(progress.getScopeType())
                ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;
    }
}
