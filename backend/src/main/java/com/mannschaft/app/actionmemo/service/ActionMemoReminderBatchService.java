package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * F02.5 行動メモ リマインド通知バッチ（Phase 6-2）。
 *
 * <p>毎分起動し、{@code user_action_memo_settings} で {@code reminder_enabled = true}
 * かつ {@code reminder_time IS NOT NULL} のユーザーを取得する。
 * 現在時刻（分単位に切り捨て）と設定時刻が一致するユーザーに対して通知を送信する。</p>
 *
 * <p>プライバシー保護: 通知にメモ内容を含めない。</p>
 *
 * <p>ShedLock により複数インスタンス起動時も重複実行を防ぐ。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionMemoReminderBatchService {

    private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");

    private final UserActionMemoSettingsRepository settingsRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    /**
     * スケジュール起動エントリポイント（毎分実行）。
     */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "actionMemoReminderBatch", lockAtMostFor = "PT50S", lockAtLeastFor = "PT0S")
    @Transactional(readOnly = true)
    public void execute() {
        executeAt(LocalTime.now(ZONE_JST).truncatedTo(ChronoUnit.MINUTES));
    }

    /**
     * テスト可能な実装本体。現在時刻を引数で受け取ることでモック不要にする。
     *
     * @param nowMinute 分単位に切り捨てた現在時刻
     */
    void executeAt(LocalTime nowMinute) {
        List<UserActionMemoSettingsEntity> targets = settingsRepository
                .findByReminderEnabledTrueAndReminderTimeIsNotNull()
                .stream()
                .filter(s -> nowMinute.equals(s.getReminderTime().truncatedTo(ChronoUnit.MINUTES)))
                .toList();

        if (targets.isEmpty()) {
            return;
        }

        int notified = 0;
        for (UserActionMemoSettingsEntity settings : targets) {
            try {
                notificationService.createNotification(
                        settings.getUserId(),
                        "ACTION_MEMO_REMINDER",
                        NotificationPriority.NORMAL,
                        "行動メモのリマインド",
                        "今日の行動メモを記録しましょう",
                        "ACTION_MEMO",
                        null,
                        NotificationScopeType.PERSONAL,
                        settings.getUserId(),
                        "/action-memo",
                        null
                );
                notified++;
            } catch (Exception e) {
                log.error("行動メモリマインド送信失敗: userId={}, error={}", settings.getUserId(), e.getMessage());
            }
        }

        log.info("行動メモリマインドバッチ完了: 対象{}件, 通知{}件", targets.size(), notified);
        auditLogService.record("ACTION_MEMO_REMINDER_BATCH", null, null, null, null, null, null, null,
                "{\"targets\":" + targets.size() + ",\"notified\":" + notified + "}");
    }
}
