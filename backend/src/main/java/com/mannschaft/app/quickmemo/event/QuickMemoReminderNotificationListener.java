package com.mannschaft.app.quickmemo.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ポイっとメモ リマインド通知の配送リスナー（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>{@code QuickMemoReminderRunner#markRemindersSent} の独立トランザクションが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。<b>単一受信者</b>の金型として
 * 第1群の {@code ContactInviteUsedNotificationListener} と同型。</p>
 *
 * <h2>意図的な挙動変更: Push/WebSocket 配信が付く</h2>
 * <p>是正前は {@code notificationService.createNotification} を直接呼ぶだけで dispatch しておらず、
 * {@code QUICK_MEMO_REMINDER} は <b>DB 保存のみ</b>（Push / WebSocket 配信なし）だった。本リスナーは
 * {@link NotificationDeliveryRunner#sendOne}（= create + dispatch）を使うため、以後この通知は
 * <b>Push / WebSocket でも配信される</b>。第1群の {@code CirculationReminderNotificationListener} と同じく、
 * 型に寄せた結果として<b>意図的に受け入れた挙動変更</b>であり退行ではない
 * （リマインドは受信者本人宛の即時性のある通知であり、届かなければ機能の意味が薄い）。</p>
 *
 * <h2>{@code sourceId} が {@code null} である理由</h2>
 * <p>本通知はユーザー単位に<b>集約された</b>リマインドであり、特定の 1 メモを指さない。是正前から
 * {@code sourceType=QUICK_MEMO} / {@code sourceId=null} であり、本ロットで変更していない
 * （visibility ガードを迂回する目的で {@code null} にしたものではない）。</p>
 *
 * <h2>プライバシー</h2>
 * <p>F02.5 H2 対応として、通知文言にメモのタイトル・内容を含めない。埋めるのは件数のみ。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickMemoReminderNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "ポイっとメモは棚卸し台帳 todo-memo で beta=コア・gate_key=null の常時提供機能であり、"
                    + "リマインド通知だけを止める停止条件が存在しないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQuickMemoReminderNotification(QuickMemoReminderNotificationEvent event) {
        if (event.recipientUserId() == null || event.memoCount() <= 0) {
            return;
        }

        // 単一受信者のため locale 解決も配送も同じ try に入れてよい（巻き添えになる他受信者がいない）。
        try {
            Locale locale = resolveLocale(event.recipientUserId());
            NotificationDeliveryRequest request = buildRequest(event, locale);
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                // deny のみのときは WARN に留め、ERROR と混ぜない。
                log.warn("ポイっとメモリマインド通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, notificationType={}, sourceType={}, memoCount={}",
                        request.recipientUserId(), request.notificationType(),
                        request.sourceType(), event.memoCount());
            }
        } catch (Exception e) {
            // 非同期イベント失敗の監査記録（規約上必須）。catch は TX 外なので rollback で消えない。
            log.error("ポイっとメモリマインド通知の配送に失敗しました: recipientUserId={}, memoCount={}",
                    event.recipientUserId(), event.memoCount(), e);
        }
    }

    /** 受信者の locale を解決する（解決自体の失敗は既定 locale で継続する）。 */
    private Locale resolveLocale(Long userId) {
        try {
            return Locale.forLanguageTag(userLocaleCache.getLocale(userId));
        } catch (Exception e) {
            log.warn("ポイっとメモリマインドの locale 解決に失敗（既定 locale で継続）: recipientUserId={}, error={}",
                    userId, e.getMessage());
            return Locale.JAPANESE;
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(QuickMemoReminderNotificationEvent event, Locale locale) {
        return new NotificationDeliveryRequest(
                event.recipientUserId(),
                "QUICK_MEMO_REMINDER",
                NotificationPriority.NORMAL,
                messageSource.getMessage(
                        "notification.quickmemo.reminder.title", null, "ポイっとメモのリマインド", locale),
                messageSource.getMessage(
                        "notification.quickmemo.reminder.body",
                        new Object[]{event.memoCount()},
                        "未整理のメモが" + event.memoCount() + "件あります", locale),
                "QUICK_MEMO",
                null,
                NotificationScopeType.PERSONAL,
                event.recipientUserId(),
                "/quick-memos?status=UNSORTED",
                null);
    }
}
