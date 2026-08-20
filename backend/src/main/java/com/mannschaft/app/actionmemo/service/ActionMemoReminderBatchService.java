package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * F02.5 行動メモ リマインド通知バッチ（Phase 6-2）。
 *
 * <p>毎分起動し、{@code user_action_memo_settings} で {@code reminder_enabled = true}
 * かつ {@code reminder_time IS NOT NULL} のユーザーを取得する。
 * 各ユーザーの設定タイムゾーンで現在時刻（分単位）と設定時刻が一致するユーザーに通知を送信する。</p>
 *
 * <p><b>タイムゾーン対応:</b>
 * ユーザーが設定した {@code reminder_time} はユーザーのローカル時刻として保存されている。
 * バッチは UTC 現在時刻を {@link UserRepository#findTimezoneById} で取得したユーザー TZ に
 * 変換してから比較することで、JST 固定だった旧実装のバグを根治する。
 * ユーザーの TZ が取得できない・不正な場合は {@code Asia/Tokyo} にフォールバックする。</p>
 *
 * <p>プライバシー保護: 通知にメモ内容を含めない。</p>
 *
 * <p>ShedLock により複数インスタンス起動時も重複実行を防ぐ。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionMemoReminderBatchService {

    /** タイムゾーン取得失敗時のフォールバック */
    private static final ZoneId ZONE_FALLBACK = ZoneId.of("Asia/Tokyo");

    private final UserActionMemoSettingsRepository settingsRepository;
    // TODO: actionmemoドメインとnotificationドメイン・authドメイン(AuditLogService/UserRepository)をまたいでいる。将来はActionMemoReminderTriggeredEventで分離予定
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;

    /**
     * スケジュール起動エントリポイント（毎分実行）。
     *
     * <p>UTC 現在時刻を基準に全ユーザーをユーザーTZで評価する。
     * cron は UTC zone で毎分起動することで、全TZをカバーする。</p>
     */
    @BatchEndpoint(name = "actionmemo-reminder", description = "行動メモのリマインド通知を毎分送信する")
    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "actionMemoReminderBatch", lockAtMostFor = "PT3M", lockAtLeastFor = "PT0S")
    @Transactional(readOnly = true)
    public void execute() {
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneId.of("UTC"));
        executeAt(nowUtc);
    }

    /**
     * テスト可能な実装本体（UTC {@link ZonedDateTime} を引数で受け取る）。
     *
     * <p>各ユーザーの TZ に変換してから {@code reminder_time} と比較する。</p>
     *
     * @param nowUtc UTC 基準の現在日時
     */
    void executeAt(ZonedDateTime nowUtc) {
        List<UserActionMemoSettingsEntity> allSettings = settingsRepository
                .findByReminderEnabledTrueAndReminderTimeIsNotNull();

        if (allSettings.isEmpty()) {
            return;
        }

        // Issue #2715 CMP-055 ロットC-6: 受信者ごとに locale が異なるため、ループの外で一括解決する（N+1 防止）。
        // Codex 検分是正（PR #2873）: バルク取得自体を try で隔離し、失敗時は既定 locale ("ja") で
        // 継続する。ループ外で無防備に呼ぶと、この一括解決だけで全受信者分の通知処理が丸ごと止まる。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(
                    allSettings.stream().map(UserActionMemoSettingsEntity::getUserId).toList());
        } catch (Exception e) {
            log.warn("locale 一括解決に失敗（既定 locale で継続）: error={}", e.getMessage());
            locales = Map.of();
        }

        int notified = 0;
        int totalTargets = 0;
        for (UserActionMemoSettingsEntity settings : allSettings) {
            ZoneId userZone = resolveUserZone(settings.getUserId());
            ZonedDateTime nowInUserZone = nowUtc.withZoneSameInstant(userZone);
            LocalTime nowMinute = nowInUserZone.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
            LocalDate today = nowInUserZone.toLocalDate();

            if (!nowMinute.equals(settings.getReminderTime().truncatedTo(ChronoUnit.MINUTES))) {
                continue;
            }

            totalTargets++;
            try {
                Locale locale = Locale.forLanguageTag(
                        locales.getOrDefault(settings.getUserId(), "ja"));
                notificationService.createNotification(
                        settings.getUserId(),
                        "ACTION_MEMO_REMINDER",
                        NotificationPriority.NORMAL,
                        messageSource.getMessage(
                                "notification.actionmemo.reminder.title", null,
                                "行動メモのリマインド", locale),
                        messageSource.getMessage(
                                "notification.actionmemo.reminder.body", null,
                                "今日の行動メモを記録しましょう", locale),
                        "ACTION_MEMO",
                        null,
                        NotificationScopeType.PERSONAL,
                        settings.getUserId(),
                        "/action-memo?date=" + today,
                        null
                );
                notified++;
            } catch (Exception e) {
                log.error("行動メモリマインド送信失敗: userId={}, error={}", settings.getUserId(), e.getMessage());
            }
        }

        if (totalTargets > 0) {
            log.info("行動メモリマインドバッチ完了: 対象{}件, 通知{}件", totalTargets, notified);
            auditLogService.record("ACTION_MEMO_REMINDER_BATCH", null, null, null, null, null, null, null,
                    "{\"targets\":" + totalTargets + ",\"notified\":" + notified + "}");
        }
    }

    /**
     * 後方互換テスト用オーバーロード。
     * JST 固定時刻を渡して動作確認するテストが既存の場合に使用する。
     *
     * @param nowMinute 分単位に切り捨てた現在時刻（JST 固定と仮定）
     */
    void executeAt(LocalTime nowMinute) {
        executeAt(nowMinute, LocalDate.now(ZONE_FALLBACK));
    }

    /**
     * 後方互換テスト用オーバーロード。
     * JST 固定時刻・日付を渡して動作確認するテストが既存の場合に使用する。
     *
     * @param nowMinute 分単位に切り捨てた現在時刻（JST 固定と仮定）
     * @param today     通知の actionUrl に埋め込む日付
     */
    void executeAt(LocalTime nowMinute, LocalDate today) {
        List<UserActionMemoSettingsEntity> targets = settingsRepository
                .findByReminderEnabledTrueAndReminderTimeIsNotNull()
                .stream()
                .filter(s -> nowMinute.equals(s.getReminderTime().truncatedTo(ChronoUnit.MINUTES)))
                .toList();

        if (targets.isEmpty()) {
            return;
        }

        String todayStr = today.toString(); // "YYYY-MM-DD"
        // Codex 検分是正（PR #2873）: バルク取得自体を try で隔離し、失敗時は既定 locale ("ja") で継続する。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(
                    targets.stream().map(UserActionMemoSettingsEntity::getUserId).toList());
        } catch (Exception e) {
            log.warn("locale 一括解決に失敗（既定 locale で継続）: error={}", e.getMessage());
            locales = Map.of();
        }
        int notified = 0;
        for (UserActionMemoSettingsEntity settings : targets) {
            try {
                Locale locale = Locale.forLanguageTag(
                        locales.getOrDefault(settings.getUserId(), "ja"));
                notificationService.createNotification(
                        settings.getUserId(),
                        "ACTION_MEMO_REMINDER",
                        NotificationPriority.NORMAL,
                        messageSource.getMessage(
                                "notification.actionmemo.reminder.title", null,
                                "行動メモのリマインド", locale),
                        messageSource.getMessage(
                                "notification.actionmemo.reminder.body", null,
                                "今日の行動メモを記録しましょう", locale),
                        "ACTION_MEMO",
                        null,
                        NotificationScopeType.PERSONAL,
                        settings.getUserId(),
                        "/action-memo?date=" + todayStr,
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

    /**
     * ユーザーの設定タイムゾーンを解決する。
     *
     * <p>ユーザーが存在しない、タイムゾーンが未設定、または不正な TZ 文字列の場合は
     * {@code Asia/Tokyo} にフォールバックする。</p>
     *
     * @param userId ユーザーID
     * @return 解決された {@link ZoneId}
     */
    private ZoneId resolveUserZone(Long userId) {
        return userRepository.findTimezoneById(userId)
                .map(tz -> {
                    try {
                        return ZoneId.of(tz);
                    } catch (Exception e) {
                        log.warn("不正なタイムゾーン設定: userId={}, tz={}, フォールバック={}", userId, tz, ZONE_FALLBACK);
                        return ZONE_FALLBACK;
                    }
                })
                .orElse(ZONE_FALLBACK);
    }
}
