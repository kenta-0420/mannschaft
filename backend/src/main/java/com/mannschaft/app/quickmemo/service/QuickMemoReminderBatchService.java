package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ポイっとメモ リマインド送信バッチ。
 * 30分ごとに未送信のリマインドを確認し、ユーザー単位で集約して通知する。
 * プライバシー保護: 通知文言にメモタイトル・内容を含めない。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickMemoReminderBatchService {

    private static final int BATCH_LIMIT = 10000;

    private final QuickMemoRepository memoRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    @Scheduled(cron = "0 */30 * * * *")
    @Transactional
    public void execute() {
        LocalDateTime now = LocalDateTime.now();
        log.info("リマインドバッチ開始: {}", now);

        List<QuickMemoEntity> targets = memoRepository.findReminderTargets(now, PageRequest.of(0, BATCH_LIMIT));
        if (targets.isEmpty()) {
            return;
        }

        // ユーザー単位に集約してリマインドを送信
        Map<Long, List<QuickMemoEntity>> byUser = targets.stream()
                .collect(Collectors.groupingBy(QuickMemoEntity::getUserId));

        int totalNotified = 0;
        for (Map.Entry<Long, List<QuickMemoEntity>> entry : byUser.entrySet()) {
            Long userId = entry.getKey();
            List<QuickMemoEntity> userMemos = entry.getValue();

            try {
                sendReminderNotification(userId, userMemos, now);
                totalNotified++;
            } catch (Exception e) {
                log.error("リマインド送信失敗: userId={}, error={}", userId, e.getMessage());
            }
        }

        log.info("リマインドバッチ完了: 対象{}件, 通知{}ユーザー", targets.size(), totalNotified);
        auditLogService.record("QUICK_MEMO_REMINDER_BATCH", null, null, null, null, null, null, null,
                "{\"targetMemos\":" + targets.size() + ",\"notifiedUsers\":" + totalNotified + "}");
    }

    private void sendReminderNotification(Long userId, List<QuickMemoEntity> memos, LocalDateTime now) {
        int count = memos.size();
        // タイトル・内容は含めない（H2 プライバシー対応）
        String title = "ポイっとメモのリマインド";
        String body = "未整理のメモが" + count + "件あります";

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
