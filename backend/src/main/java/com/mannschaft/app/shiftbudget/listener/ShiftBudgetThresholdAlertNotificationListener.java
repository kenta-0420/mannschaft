package com.mannschaft.app.shiftbudget.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.event.BudgetThresholdAlertTriggeredEvent;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetFailedEventService;
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
 * F08.7 シフト予算 閾値超過警告の配送リスナー（Issue #2990 L13）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code ThresholdAlertEvaluationService#sendNotifications} が
 * {@code @Transactional(REQUIRES_NEW)} な {@code evaluateAndTrigger} の内側から
 * {@code notificationHelper.notifyAllLocalized(...)} を呼んでいた（{@code TX_NOTIFY_IN_TRY}）。</p>
 *
 * <p><b>try/catch で握っていたにもかかわらず、業務データは巻き戻る</b>。
 * {@code notifyAllLocalized} の下流は {@code NotificationService#createNotification}
 * （{@code @Transactional} 既定の {@code REQUIRED}）であり、呼び出し元の
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
 * {@link NotificationHelper#notifyAllLocalized} 内の受信者ごとの try/catch=被害半径
 * （1 名への配送が落ちても残りの受信者へ配送が続く）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftBudgetThresholdAlertNotificationListener {

    private final NotificationHelper notificationHelper;
    private final ShiftBudgetFailedEventService failedEventService;
    private final MessageSource messageSource;

    /** 件名の i18n キー（failed_events の payload にも保存してリトライ経路で再利用する）。 */
    static final String TITLE_KEY = "notification.shiftBudget.thresholdAlert.title";

    /**
     * 閾値超過警告の発火イベントを受け取り、受信者ごとの locale で通知を配送する。
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
        // failed_events 記録用（運用向けの固定ログ。受信者ごとの locale とは独立に ja で確定させる）。
        String title = title(thresholdPercent, Locale.JAPANESE);
        String body = body(thresholdPercent, Locale.JAPANESE);

        try {
            // Issue #2715 CMP-055 ロットC-4: 受信者 locale に応じて件名・本文を組み立てる
            // （locale 一括解決は notifyAllLocalized 内部の UserLocaleCache が担う）。
            notificationHelper.notifyAllLocalized(
                    recipientUserIds,
                    "SHIFT_BUDGET_THRESHOLD_ALERT",
                    "SHIFT_BUDGET_ALLOCATION",
                    event.allocationId(),
                    NotificationScopeType.ORGANIZATION,
                    event.organizationId(),
                    actionUrl,
                    null,  // システム自動発火、actor なし
                    (userId, locale) -> new NotificationHelper.LocalizedMessage(
                            title(thresholdPercent, locale), body(thresholdPercent, locale)));
        } catch (Exception e) {
            // 受信者ごとの失敗は notifyAllLocalized 内部で握られて次の受信者へ進む。ここへ来るのは
            // 全体で総崩れする例外（visibility 絞り込み・locale 一括解決での DB 接続喪失など）だけである。
            log.error("F08.7: 通知一括送信失敗（握りつぶし）: allocId={}, threshold={}%, recipients={}",
                    event.allocationId(), thresholdPercent, recipientUserIds.size(), e);
            try {
                failedEventService.recordFailure(
                        event.organizationId(),
                        ShiftBudgetFailedEventType.NOTIFICATION_SEND,
                        event.allocationId(),
                        // Map.of は 10 組までなので ofEntries を使う（キーは 11 組ある）。
                        Map.ofEntries(
                                Map.entry("user_ids", recipientUserIds),
                                Map.entry("type", "SHIFT_BUDGET_THRESHOLD_ALERT"),
                                // Issue #2908: ja 固定の title / body は運用ログ・フォレンジック用。
                                // リトライ経路は下の i18n キーから受信者 locale で組み立て直す。
                                Map.entry("title", title),
                                Map.entry("body", body),
                                Map.entry("title_key", TITLE_KEY),
                                Map.entry("body_key", bodyKeyForThreshold(thresholdPercent)),
                                Map.entry("source_type", "SHIFT_BUDGET_ALLOCATION"),
                                Map.entry("source_id", event.allocationId()),
                                Map.entry("scope_id", event.organizationId()),
                                Map.entry("action_url", actionUrl),
                                Map.entry("threshold_percent", thresholdPercent)
                        ),
                        e.getClass().getSimpleName() + ": " + e.getMessage()
                );
            } catch (Exception recEx) {
                log.error("F08.7: NOTIFICATION_SEND failed_events 記録自体も失敗（諦め）: allocId={}",
                        event.allocationId(), recEx);
            }
        }
    }

    /** 閾値ごとの通知件名（i18n）。 */
    private String title(int thresholdPercent, Locale locale) {
        return messageSource.getMessage(
                TITLE_KEY,
                new Object[]{thresholdPercent},
                "シフト予算 警告 (" + thresholdPercent + "%)", locale);
    }

    /** 閾値ごとの通知本文（i18n）。 */
    private String body(int thresholdPercent, Locale locale) {
        return messageSource.getMessage(
                bodyKeyForThreshold(thresholdPercent),
                new Object[]{thresholdPercent},
                bodyForThreshold(thresholdPercent), locale);
    }

    /**
     * 閾値ごとの本文 i18n キー。
     *
     * @param thresholdPercent 閾値
     * @return ロケールファイル参照用のキー
     */
    private String bodyKeyForThreshold(int thresholdPercent) {
        return switch (thresholdPercent) {
            case 80 -> "notification.shiftBudget.thresholdAlert.body80";
            case 100 -> "notification.shiftBudget.thresholdAlert.body100";
            case 120 -> "notification.shiftBudget.thresholdAlert.body120";
            default -> "notification.shiftBudget.thresholdAlert.bodyOther";
        };
    }

    /** 閾値ごとの通知本文の既定値（ロケールファイルにキーが無い場合のフォールバック）。 */
    private String bodyForThreshold(int thresholdPercent) {
        return switch (thresholdPercent) {
            case 80 -> "予算 80% に到達しました";
            case 100 -> "予算を超過しました";
            case 120 -> "予算 120% を超過しました（重大）";
            default -> "シフト予算が閾値 " + thresholdPercent + "% に到達しました";
        };
    }
}
