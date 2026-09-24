package com.mannschaft.app.shiftbudget.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.ShiftBudgetThresholdAlertMessages;
import com.mannschaft.app.shiftbudget.event.BudgetThresholdAlertTriggeredEvent;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetFailedEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * F08.7 シフト予算 閾値超過警告の配送リスナー（Issue #2990 L13）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code ThresholdAlertEvaluationService#sendNotifications} が
 * {@code @Transactional(REQUIRES_NEW)} な {@code evaluateAndTrigger} の内側から
 * {@code notificationHelper.notifyAllLocalized(...)} を呼んでいた（{@code TX_NOTIFY_IN_TRY}）。</p>
 *
 * <p><b>try/catch で握っていたにもかかわらず、業務データは巻き戻る</b>。
 * その下流 {@code NotificationService#createNotification}
 * （{@code @Transactional} 既定の {@code REQUIRED}）は呼び出し元の
 * トランザクションにそのまま参加する。DB 層で例外が起きるとそのトランザクションは
 * rollback-only になるため、catch で握っても commit 時に
 * {@code UnexpectedRollbackException} となり、同一トランザクションで書いた
 * <b>{@code budget_threshold_alerts} の INSERT・{@code workflow_request_id} の書戻し・
 * 監査ログ {@code BUDGET_THRESHOLD_ALERT_TRIGGERED} がまとめて消える</b>。
 * この性質は {@code NotificationHelper#notifyAllPreAuthorizedLocalized} の実装コメントが
 * 「非バルク経路（notify / notifyAll / notifyAllLocalized）は依然として rollback-only を伝播する。
 * それらの呼び出し元は業務TX内で通知を発火せず AFTER_COMMIT 境界の後へ移すこと」と明記している。</p>
 *
 * <p>しかも失敗記録側の {@code failedEventService.recordFailure} は
 * {@code REQUIRES_NEW} なので<b>生き残る</b>。結果として
 * 「alert 行は存在しないのに『その alert の通知に失敗した』failed_event だけが残る」
 * という食い違いが生じ、リトライバッチが同じ閾値を再評価して警告を作り直す。</p>
 *
 * <h2>是正後</h2>
 * <p>業務トランザクションの内側では {@link BudgetThresholdAlertTriggeredEvent} を publish
 * するに留め、本リスナーが {@code AFTER_COMMIT} + {@code @Async("event-pool")} で受け取って配送する。
 * 3 つの技法はそれぞれ別の問題を解く: {@code AFTER_COMMIT}=因果（alert 行の確定後に通知が出る）、
 * {@code @Async}=遅延の切り離し（配送の失敗が業務スレッドへ例外として返らない）、
 * 受信者ごとの {@link NotificationDeliveryRunner#sendOne}（1 件ごと {@code REQUIRES_NEW}）
 * =被害半径（1 名への配送が落ちても残りの受信者へ配送が続く）。</p>
 *
 * <h2>なぜ {@code notifyAllLocalized} を使わないのか（Codex 検分 P1-a）</h2>
 * <p>{@code notifyAllLocalized} は受信者ごとの {@code notify} 例外を<b>内部で捕捉して
 * 呼び出し元へ何も返さない</b>。そのため、それを使うと「通知行の作成が一部あるいは全件失敗しても
 * 外側の catch が実行されず {@code NOTIFICATION_SEND} の failed event が残らない」。
 * locale 解決の後で DB 障害が起きると、<b>alert は確定済みなのに通知は失われ、再送経路も無い</b>
 * という回復不能な状態になる。これは本 PR が
 * {@code ShiftBudgetNotificationResendService} 側で直したのと<b>同じ根</b>の欠陥である。
 * したがって配送は正規形どおり受信者ごとの {@code sendOne} で行い、
 * <b>失敗した受信者を集めて failed event に残す</b>。
 * locale の一括解決は {@link UserLocaleCache#getLocales} を直接呼んで N+1 を避ける。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftBudgetThresholdAlertNotificationListener {

    private static final String NOTIFICATION_TYPE = "SHIFT_BUDGET_THRESHOLD_ALERT";
    private static final String SOURCE_TYPE = "SHIFT_BUDGET_ALLOCATION";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final ShiftBudgetFailedEventService failedEventService;
    private final MessageSource messageSource;

    /**
     * 閾値超過警告の発火イベントを受け取り、受信者ごとの locale で通知を配送する。
     *
     * <p>配送に失敗した受信者は {@code NOTIFICATION_SEND} の failed event として記録し、
     * リトライバッチ / 管理 API の再送経路へ載せる。<b>記録に載らなければ再送の仕組みは起動しない</b>ため、
     * ここで失敗を握り潰してはならない。</p>
     *
     * @param event 閾値超過警告の発火イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "予算超過という金銭リスクの一次通知であり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすと管理者が超過に気づけないまま消化が進む。イベントは再生されない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBudgetThresholdAlertTriggered(BudgetThresholdAlertTriggeredEvent event) {
        List<Long> recipientUserIds = event.recipientUserIds();
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            log.warn("F08.7 閾値超過警告: 受信ロール 0 名のため通知配送スキップ: allocId={}, threshold={}%",
                    event.allocationId(), event.thresholdPercent());
            return;
        }
        int thresholdPercent = event.thresholdPercent();
        String actionUrl = "/shift-budget/allocations/" + event.allocationId();

        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(recipientUserIds);
        } catch (Exception e) {
            // locale の一括解決で総崩れした（DB 接続喪失など）。1 名も配送できていないので全員を記録する。
            log.error("F08.7: 受信者 locale の一括解決に失敗（全員を再送対象として記録）: "
                            + "allocId={}, threshold={}%, recipients={}",
                    event.allocationId(), thresholdPercent, recipientUserIds.size(), e);
            recordFailure(event, actionUrl, recipientUserIds, e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

        List<Long> failedUserIds = new ArrayList<>();
        String lastError = null;
        for (Long userId : recipientUserIds) {
            try {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(userId, "ja"));
                notificationDeliveryRunner.sendOne(new NotificationDeliveryRequest(
                        userId,
                        NOTIFICATION_TYPE,
                        NotificationPriority.NORMAL,
                        title(thresholdPercent, locale),
                        body(thresholdPercent, locale),
                        SOURCE_TYPE,
                        event.allocationId(),
                        NotificationScopeType.ORGANIZATION,
                        event.organizationId(),
                        actionUrl,
                        null));  // システム自動発火、actor なし
            } catch (Exception e) {
                // 1 名の失敗で残りの受信者を諦めない（被害半径の分離）。
                failedUserIds.add(userId);
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.error("F08.7 閾値超過警告の配送に失敗（当該受信者のみスキップして継続）: "
                                + "userId={}, allocId={}, threshold={}%",
                        userId, event.allocationId(), thresholdPercent, e);
            }
        }

        if (!failedUserIds.isEmpty()) {
            // 失敗した受信者<b>だけ</b>を記録する。成功済みの受信者を混ぜると再送で重複配信になる。
            recordFailure(event, actionUrl, failedUserIds, lastError);
            return;
        }
        log.info("F08.7 閾値超過警告を配送: allocId={}, threshold={}%, recipients={}",
                event.allocationId(), thresholdPercent, recipientUserIds.size());
    }

    /**
     * 配送に失敗した受信者を {@code NOTIFICATION_SEND} の failed event として記録する。
     *
     * <p>記録自体が失敗したらもう打つ手が無いので ERROR ログだけ残して諦める
     * （ここで例外を投げても {@code @Async} の配送スレッドを殺すだけで誰も受け取らない）。</p>
     */
    private void recordFailure(BudgetThresholdAlertTriggeredEvent event, String actionUrl,
                               List<Long> failedUserIds, String errorMessage) {
        int thresholdPercent = event.thresholdPercent();
        // 運用ログ・フォレンジック用の固定文言（受信者ごとの locale とは独立に ja で確定させる）。
        // 再送は下の title_key / body_key から受信者 locale で組み立て直す（Issue #2908）。
        String title = title(thresholdPercent, Locale.JAPANESE);
        String body = body(thresholdPercent, Locale.JAPANESE);
        try {
            failedEventService.recordFailure(
                    event.organizationId(),
                    ShiftBudgetFailedEventType.NOTIFICATION_SEND,
                    event.allocationId(),
                    // Map.of は 10 組までなので ofEntries を使う（キーは 11 組ある）。
                    Map.ofEntries(
                            Map.entry("user_ids", failedUserIds),
                            Map.entry("type", NOTIFICATION_TYPE),
                            Map.entry("title", title),
                            Map.entry("body", body),
                            Map.entry("title_key", ShiftBudgetThresholdAlertMessages.TITLE_KEY),
                            Map.entry("body_key", ShiftBudgetThresholdAlertMessages.bodyKey(thresholdPercent)),
                            Map.entry("source_type", SOURCE_TYPE),
                            Map.entry("source_id", event.allocationId()),
                            Map.entry("scope_id", event.organizationId()),
                            Map.entry("action_url", actionUrl),
                            Map.entry("threshold_percent", thresholdPercent)
                    ),
                    errorMessage
            );
        } catch (Exception recEx) {
            log.error("F08.7: NOTIFICATION_SEND failed_events 記録自体も失敗（諦め）: allocId={}, failedUserIds={}",
                    event.allocationId(), failedUserIds, recEx);
        }
    }

    /** 閾値ごとの通知件名（i18n）。 */
    private String title(int thresholdPercent, Locale locale) {
        return messageSource.getMessage(
                ShiftBudgetThresholdAlertMessages.TITLE_KEY,
                new Object[]{thresholdPercent},
                ShiftBudgetThresholdAlertMessages.defaultTitle(thresholdPercent), locale);
    }

    /** 閾値ごとの通知本文（i18n）。 */
    private String body(int thresholdPercent, Locale locale) {
        return messageSource.getMessage(
                ShiftBudgetThresholdAlertMessages.bodyKey(thresholdPercent),
                new Object[]{thresholdPercent},
                ShiftBudgetThresholdAlertMessages.defaultBody(thresholdPercent), locale);
    }
}
