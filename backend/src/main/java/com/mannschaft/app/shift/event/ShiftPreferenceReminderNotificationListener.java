package com.mannschaft.app.shift.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.shift.service.ShiftPreferenceReminderRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * シフト希望提出リマインド通知の配送リスナー（Issue #2997 / CMP-260827-1152 G6）。
 *
 * <p>定期バッチの項目TX（{@code ShiftPreferenceReminderRunner#remindOne}）または手動リマインドのTX
 * （{@code triggerManualReminder}）が commit された後（{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。
 * 受信者ごとの組み立て＋配送を内側 try で隔離する形は {@link ShiftSwapExpiredNotificationListener} と同型。</p>
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code NotificationHelper#notifyAllLocalized} を業務トランザクションの内側（定期バッチは全スケジュールを
 * 1 TX で包んだまま）で呼んでいた。{@code notify} は既定の {@code REQUIRED} で業務TXに参加するため、
 * 1 通の DB 例外が rollback-only を残し、catch して続行したつもりでも<b>他スケジュールの送信済みフラグまで
 * コミット時に巻き戻って</b>翌回に二重送信になりうる状態だった。</p>
 *
 * <h2>配送意味論（是正前と同一）</h2>
 * <ul>
 *   <li>受信者集合・通知種別・文面・locale（受信者ごと。既定 ja）・遷移先・スコープ（TEAM）・actor（なし）は同一。</li>
 *   <li>可視性判定は {@code sendOne} → {@code createNotification} の事前認可に任せる。deny は例外ではなく
 *       {@code VISIBILITY_DENIED} として返り、この受信者ぶんは送られない（是正前の絞り込みと同じ結果）。</li>
 * </ul>
 *
 * <h2>送信済みフラグとの関係（AC-D・マスター裁可「配送後に印を立てる」）</h2>
 * <p>定期バッチ（48h / 24h）では、受信者ごとの {@code sendOne} を終えた<b>後</b>に
 * {@link ShiftPreferenceReminderRunner#markSent}（別の {@code REQUIRES_NEW}）で印を立てる。</p>
 * <ul>
 *   <li>1 人以上に配送できた、または deny があれば立てる。一部の受信者だけ失敗しても立てる
 *       （成功済みにも失敗した受信者にも再送しない）。</li>
 *   <li>全員が例外で失敗したときは立てない。次のバッチ実行で再送される。</li>
 *   <li>印の保存が失敗したときは ERROR ログのみ。配送済みで印が無いため次回に再送されうる
 *       （旧実装は通知と印が同一TXで一緒に巻き戻っていた）。</li>
 *   <li>手動リマインドは印を持たないので何もしない。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftPreferenceReminderNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    private final ShiftPreferenceReminderRunner reminderRunner;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
            gateKeys = {"FEATURE_SHIFT_ENABLED"},
            reason = "シフトは棚卸し台帳で beta=停止・gate_key=FEATURE_SHIFT_ENABLED を持つ隔離対象であり、"
                    + "機能停止中に提出リマインドだけが利用者へ飛ぶことを避ける。送信済みフラグは"
                    + "業務TX側で確定済みで、ドロップされるのは通知だけであるため整合性は壊れない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftPreferenceReminderNotification(ShiftPreferenceReminderNotificationEvent event) {
        List<Long> recipients = event.recipientUserIds();
        if (recipients == null || recipients.isEmpty()) {
            return;
        }

        // locale は一括解決（N+1 防止）。解決自体の失敗は既定 locale で継続する。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(recipients);
        } catch (Exception e) {
            log.warn("シフト希望リマインドの locale 一括解決に失敗（既定 locale で継続）: scheduleId={}, error={}",
                    event.scheduleId(), e.getMessage());
            locales = Map.of();
        }

        int delivered = 0;
        int denied = 0;
        int failed = 0;
        Long firstFailedUserId = null;
        for (Long recipientUserId : recipients) {
            try {
                // 組み立ても受信者単位で内側 try に入れる（1人ぶんの組み立て失敗が他を巻き添えにしない）。
                NotificationDeliveryRequest request = buildRequest(event, recipientUserId,
                        Locale.forLanguageTag(locales.getOrDefault(recipientUserId, "ja")));
                NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
                if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                    denied++;
                    log.warn("シフト希望リマインドが visibility deny によりスキップされました: "
                                    + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                            request.recipientUserId(), request.notificationType(),
                            request.sourceType(), request.sourceId());
                } else {
                    delivered++;
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedUserId == null) {
                    firstFailedUserId = recipientUserId;
                }
                log.error("シフト希望リマインドの配送に失敗しました: recipientUserId={}, scheduleId={}, type={}",
                        recipientUserId, event.scheduleId(), event.kind().notificationType(), e);
            }
        }

        markSentIfDelivered(event, delivered, denied);

        // 集計ログのレベルは個別ログと揃える。deny は正常系なので WARN、例外が1件でもあれば ERROR。
        String summary = "シフト希望リマインドの一括配送結果: type={}, scheduleId={}, total={}, failed={}, denied={}, "
                + "firstFailedUserId={}";
        if (failed > 0) {
            log.error(summary, event.kind().notificationType(), event.scheduleId(), recipients.size(),
                    failed, denied, firstFailedUserId);
        } else if (denied > 0) {
            log.warn(summary, event.kind().notificationType(), event.scheduleId(), recipients.size(),
                    failed, denied, firstFailedUserId);
        } else {
            log.info("シフト希望リマインド送信: type={}, scheduleId={}, 未提出人数={}",
                    event.kind().notificationType(), event.scheduleId(), recipients.size());
        }
    }

    /** 配送後の印。1 人以上に配送できた、または deny があるときだけ立てる（全員例外失敗なら立てず再送に回す）。 */
    private void markSentIfDelivered(ShiftPreferenceReminderNotificationEvent event, int delivered, int denied) {
        if (event.kind() == ShiftPreferenceReminderKind.MANUAL || (delivered == 0 && denied == 0)) {
            return;
        }
        try {
            reminderRunner.markSent(event.scheduleId(), event.kind());
        } catch (Exception e) {
            log.error("シフト希望リマインドの送信済みフラグ保存に失敗しました（次回バッチで再送されうる）: "
                    + "scheduleId={}, type={}", event.scheduleId(), event.kind().notificationType(), e);
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(ShiftPreferenceReminderNotificationEvent event,
                                                     Long recipientUserId, Locale locale) {
        ShiftPreferenceReminderKind kind = event.kind();
        return new NotificationDeliveryRequest(
                recipientUserId,
                kind.notificationType(),
                NotificationPriority.NORMAL,
                messageSource.getMessage(kind.titleKey(), null, kind.titleDefault(), locale),
                messageSource.getMessage(kind.bodyKey(), new Object[]{event.scheduleTitle()},
                        kind.bodyDefault(event.scheduleTitle()), locale),
                "SHIFT_SCHEDULE",
                event.scheduleId(),
                NotificationScopeType.TEAM,
                event.teamId(),
                "/my/shift-request?teamId=" + event.teamId() + "&scheduleId=" + event.scheduleId(),
                null);
    }
}
