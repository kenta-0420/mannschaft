package com.mannschaft.app.shift.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * シフト交代申請の期限切れ自動キャンセル通知の配送リスナー（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>{@code ShiftSwapExpiryRunner#cancelOne} の独立トランザクションが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。<b>複数受信者</b>の金型として
 * 第1群の {@code OnboardingReminderNotificationListener} と同型
 * （受信者リストの解決は Runner 側で 1 回、組み立て＋配送は受信者ごとの内側 try で隔離）。</p>
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は申請者と相手の 2 通を業務トランザクション内で送り、失敗を {@code notifySwapExpired} の
 * 内側で WARN にして握っていた。{@code notificationHelper.notify} は既定の {@code REQUIRED} 伝播で
 * 業務トランザクションに参加するため、1 通の DB 例外が rollback-only を残し、
 * <b>キャンセルそのものと他の申請ぶんまで巻き戻していた</b>。</p>
 *
 * <h2>削除済み source を参照しないことの確認</h2>
 * <p>{@code sourceType=SHIFT_SWAP_REQUEST} / {@code sourceId=申請ID} を参照するが、自動キャンセルは
 * 行を削除も論理削除もしない（{@code status} を {@code CANCELLED} にするだけ）。よってコミット後発火でも
 * source は生存しており「静かな deny」は発生しない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftSwapExpiredNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
            gateKeys = {"FEATURE_SHIFT_ENABLED"},
            reason = "シフトは棚卸し台帳で beta=停止・gate_key=FEATURE_SHIFT_ENABLED を持つ隔離対象であり、"
                    + "機能停止中に交代申請の期限切れ通知だけが利用者へ飛ぶことを避ける。キャンセル自体は"
                    + "バッチ側で確定済みで、ドロップされるのは通知だけであるため整合性は壊れない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftSwapExpiredNotification(ShiftSwapExpiredNotificationEvent event) {
        List<Long> recipients = event.recipientUserIds();
        if (recipients == null || recipients.isEmpty()) {
            return;
        }

        // locale は一括解決（N+1 防止）。解決自体の失敗は既定 locale で継続する。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(recipients);
        } catch (Exception e) {
            log.warn("スワップ期限切れ通知の locale 一括解決に失敗（既定 locale で継続）: swapId={}, error={}",
                    event.swapId(), e.getMessage());
            locales = Map.of();
        }

        int denied = 0;
        int failed = 0;
        Long firstFailedUserId = null;
        for (Long recipientUserId : recipients) {
            try {
                // 組み立ても受信者単位で内側 try に入れる（1人ぶんの組み立て失敗が他を巻き添えにしない）。
                NotificationDeliveryRequest request = buildRequest(recipientUserId, event.swapId(),
                        Locale.forLanguageTag(locales.getOrDefault(recipientUserId, "ja")));
                NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
                if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                    denied++;
                    log.warn("スワップ期限切れ通知が visibility deny によりスキップされました: "
                                    + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                            request.recipientUserId(), request.notificationType(),
                            request.sourceType(), request.sourceId());
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedUserId == null) {
                    firstFailedUserId = recipientUserId;
                }
                log.error("スワップ期限切れ通知の配送に失敗しました: recipientUserId={}, swapId={}",
                        recipientUserId, event.swapId(), e);
            }
        }

        // 集計ログのレベルは個別ログと揃える。deny は正常系なので WARN、例外が1件でもあれば ERROR。
        if (failed > 0 || denied > 0) {
            String summary = "スワップ期限切れ通知の一括配送結果: swapId={}, total={}, failed={}, denied={}, "
                    + "firstFailedUserId={}";
            if (failed > 0) {
                log.error(summary, event.swapId(), recipients.size(), failed, denied, firstFailedUserId);
            } else {
                log.warn(summary, event.swapId(), recipients.size(), failed, denied, firstFailedUserId);
            }
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(Long recipientUserId, Long swapId, Locale locale) {
        return new NotificationDeliveryRequest(
                recipientUserId,
                "SHIFT_SWAP_EXPIRED",
                NotificationPriority.NORMAL,
                messageSource.getMessage(
                        "notification.shift.swapExpired.title", null,
                        "シフト交代申請が期限切れになりました", locale),
                messageSource.getMessage(
                        "notification.shift.swapExpired.body", null,
                        "申請から 48 時間が経過したため、シフト交代申請が自動キャンセルされました。", locale),
                "SHIFT_SWAP_REQUEST",
                swapId,
                NotificationScopeType.PERSONAL,
                recipientUserId,
                "/shifts/swap-requests/" + swapId,
                null);
    }
}
