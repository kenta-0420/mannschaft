package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageRequest;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ポイっとメモ リマインド送信バッチ。
 * 30分ごとに未送信のリマインドを確認し、ユーザー単位で集約して通知する。
 * プライバシー保護: 通知文言にメモタイトル・内容を含めない。
 *
 * <p><b>タイムゾーン:</b> {@code reminder1ScheduledAt} / {@code reminder2ScheduledAt} /
 * {@code reminder3ScheduledAt} は {@link QuickMemoService} で
 * JST（{@code Asia/Tokyo}）基準の {@link LocalDateTime} として保存される。
 * バッチ比較の {@code now} も同じ JST で取得することで基準を統一する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickMemoReminderBatchService {

    private static final int BATCH_LIMIT = 10000;
    /** reminder_xScheduledAt の保存基準と同じ TZ */
    private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");

    private final QuickMemoRepository memoRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;

    @BatchEndpoint(name = "quickmemo-reminder-dispatch", description = "ポイっとメモのリマインド通知を 30 分毎にユーザー単位で集約送信する")
    @Scheduled(cron = "0 */30 * * * *")
    // 起動間隔は 30 分。処理はリマインド対象のユーザー単位集約送信で通常は数秒〜数十秒。間隔と同値にすると 1 回の超過で二重通知になるため、
    // 間隔の 2 倍を上限とする。
    @SchedulerLock(name = "quickmemoReminderDispatch", lockAtLeastFor = "PT30S", lockAtMostFor = "PT1H")
    @Transactional
    public void execute() {
        // reminder_xScheduledAt は QuickMemoService で JST LocalDateTime として保存されるため
        // 比較用の now も同じ JST で取得する
        LocalDateTime now = LocalDateTime.now(ZONE_JST);
        log.info("リマインドバッチ開始: {}", now);

        List<QuickMemoEntity> targets = memoRepository.findReminderTargets(now, PageRequest.of(0, BATCH_LIMIT));
        if (targets.isEmpty()) {
            return;
        }

        // ユーザー単位に集約してリマインドを送信
        Map<Long, List<QuickMemoEntity>> byUser = targets.stream()
                .collect(Collectors.groupingBy(QuickMemoEntity::getUserId));

        // Issue #2715 CMP-055 ロットC-6: 受信者ごとに locale が異なるため、ループの外で一括解決する（N+1 防止）。
        // Codex 検分是正（PR #2873）: バルク取得自体を try で隔離し、失敗時は既定 locale ("ja") で継続する。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(byUser.keySet().stream().toList());
        } catch (Exception e) {
            log.warn("locale 一括解決に失敗（既定 locale で継続）: error={}", e.getMessage());
            locales = Map.of();
        }

        int totalNotified = 0;
        for (Map.Entry<Long, List<QuickMemoEntity>> entry : byUser.entrySet()) {
            Long userId = entry.getKey();
            List<QuickMemoEntity> userMemos = entry.getValue();

            try {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(userId, "ja"));
                sendReminderNotification(userId, userMemos, now, locale);
                totalNotified++;
            } catch (Exception e) {
                log.error("リマインド送信失敗: userId={}, error={}", userId, e.getMessage());
            }
        }

        log.info("リマインドバッチ完了: 対象{}件, 通知{}ユーザー", targets.size(), totalNotified);
        auditLogService.record("QUICK_MEMO_REMINDER_BATCH", null, null, null, null, null, null, null,
                "{\"targetMemos\":" + targets.size() + ",\"notifiedUsers\":" + totalNotified + "}");
    }

    private void sendReminderNotification(Long userId, List<QuickMemoEntity> memos, LocalDateTime now, Locale locale) {
        int count = memos.size();
        // タイトル・内容は含めない（H2 プライバシー対応）
        String title = messageSource.getMessage(
                "notification.quickmemo.reminder.title", null, "ポイっとメモのリマインド", locale);
        String body = messageSource.getMessage(
                "notification.quickmemo.reminder.body",
                new Object[]{count}, "未整理のメモが" + count + "件あります", locale);

        notificationService.createNotification(
                userId,
                "QUICK_MEMO_REMINDER",
                NotificationPriority.NORMAL,
                title,
                body,
                "QUICK_MEMO",
                null,
                NotificationScopeType.PERSONAL,
                userId,
                "/quick-memos?status=UNSORTED",
                null
        );

        // 送信済みを記録（各メモの対象枠のみ）
        for (QuickMemoEntity memo : memos) {
            if (memo.getReminder1ScheduledAt() != null
                    && !memo.getReminder1ScheduledAt().isAfter(now)
                    && memo.getReminder1SentAt() == null) {
                memoRepository.markReminder1Sent(memo.getId(), now);
            }
            if (memo.getReminder2ScheduledAt() != null
                    && !memo.getReminder2ScheduledAt().isAfter(now)
                    && memo.getReminder2SentAt() == null) {
                memoRepository.markReminder2Sent(memo.getId(), now);
            }
            if (memo.getReminder3ScheduledAt() != null
                    && !memo.getReminder3ScheduledAt().isAfter(now)
                    && memo.getReminder3SentAt() == null) {
                memoRepository.markReminder3Sent(memo.getId(), now);
            }
        }
    }
}
