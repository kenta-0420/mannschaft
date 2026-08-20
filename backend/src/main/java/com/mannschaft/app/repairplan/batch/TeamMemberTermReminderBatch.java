package com.mannschaft.app.repairplan.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.repairplan.entity.TeamMemberTerm;
import com.mannschaft.app.repairplan.repository.TeamMemberTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 任期終了リマインドバッチ（F08.8 Phase 5）。
 *
 * <p>毎朝 9:00 JST に起動し、30 日以内に任期終了を迎えるアクティブな理事に通知を送る。
 * テスト可能なよう {@link #executeAt(LocalDate)} で日付注入できる設計にしている。</p>
 *
 * <p>TODO: repairplan ドメインと notification / auth ドメインをまたいでいる。
 * 将来は TeamMemberTermReminderTriggeredEvent で分離予定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamMemberTermReminderBatch {

    /** 何日前から通知を送るか。 */
    private static final int REMINDER_DAYS = 30;

    private final TeamMemberTermRepository termRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;

    /**
     * スケジュール起動エントリポイント（毎朝 9:00 JST）。
     */
    @BatchEndpoint(name = "repairplan-team-member-term-reminder-daily", description = "理事任期終了 30 日前のリマインドを毎日 09:00 に通知する")
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "TeamMemberTermReminderBatch", lockAtMostFor = "PT55M")
    @Transactional(readOnly = true)
    public void execute() {
        executeAt(LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")));
    }

    /**
     * テスト可能な実装本体。対象日付を引数で受け取る。
     *
     * @param today 基準日（JST 今日）
     */
    void executeAt(LocalDate today) {
        LocalDate deadline = today.plusDays(REMINDER_DAYS);
        List<TeamMemberTerm> targets = termRepository.findByIsActiveTrueAndTermEndBetween(today, deadline);

        if (targets.isEmpty()) {
            log.debug("任期終了リマインド: 対象なし (today={}, deadline={})", today, deadline);
            return;
        }

        // Issue #2715 CMP-055 ロットC-6: 受信者ごとに locale が異なるため、ループの外で一括解決する（N+1 防止）。
        Map<Long, String> locales = userLocaleCache.getLocales(
                targets.stream().map(TeamMemberTerm::getUserId).toList());

        int notified = 0;
        for (TeamMemberTerm term : targets) {
            try {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(term.getUserId(), "ja"));
                // AC-7: 任期の日付表記もロケール化する（値そのものは変えない）。
                String datePattern = messageSource.getMessage(
                        "notification.repairplan.termReminder.datePattern", null, "yyyy年M月d日", locale);
                var fmt = java.time.format.DateTimeFormatter.ofPattern(datePattern, locale);
                long daysRemaining = java.time.temporal.ChronoUnit.DAYS.between(today, term.getTermEnd());
                String title = messageSource.getMessage(
                        "notification.repairplan.termReminder.title", null,
                        "理事任期終了のお知らせ", locale);
                String body = messageSource.getMessage(
                        "notification.repairplan.termReminder.body",
                        new Object[]{term.getTermStart().format(fmt), term.getTermEnd().format(fmt), daysRemaining},
                        "あなたの理事任期（" + term.getTermStart() + " 〜 " + term.getTermEnd() + "）が "
                                + daysRemaining + " 日以内に終了します。申し送り準備をお忘れなく。",
                        locale);
                notificationService.createNotification(
                        term.getUserId(),
                        "TERM_ENDING_REMINDER",
                        NotificationPriority.NORMAL,
                        title,
                        body,
                        "REPAIR_PLAN",
                        term.getScopeId(),
                        NotificationScopeType.TEAM,
                        term.getScopeId(),
                        "/teams/" + term.getScopeId() + "/repair-plan/handover-packs",
                        null
                );
                notified++;
            } catch (Exception e) {
                log.error("任期終了リマインド送信失敗: termId={}, userId={}", term.getId(), term.getUserId(), e);
            }
        }

        log.info("任期終了リマインドバッチ完了: 対象{}件, 通知{}件 (today={})", targets.size(), notified, today);
        auditLogService.record("TEAM_MEMBER_TERM_REMINDER_BATCH", null, null, null, null, null, null, null,
                String.format("{\"targets\":%d,\"notified\":%d,\"today\":\"%s\"}", targets.size(), notified, today));
    }
}
