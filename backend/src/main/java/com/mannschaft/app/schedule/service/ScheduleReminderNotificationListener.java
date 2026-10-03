package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.schedule.event.ReminderNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 予定リマインダー通知リスナー（機能55 第二陣）。
 *
 * <p>{@link ReminderNotificationEvent} を受信し、{@link NotificationHelper#notifyAllPreAuthorized}
 * 経由で IN_APP + PUSH を配信する。リマインダーバッチの {@code @Transactional} 内で同期実行され、
 * 通知作成は同一トランザクションで確定する（既存の代理出席通知と同じ作法）。</p>
 *
 * <h2>是正（Issue #2990 L8）— 何が問題だったか</h2>
 * <p>是正前の本リスナーは素の {@code @EventListener} であり、リマインダーバッチ／即時リマインドの
 * {@code @Transactional} の内側で<b>業務コミット前に同期実行</b>されていた。つまり通知が先に確定し、
 * 業務側（{@code markAsNotified} / {@code markAsSent}）が後でロールバックすると
 * <b>「送信済みの記録は消えたのに通知だけ届いている」逆向きの不整合</b>が残る。原則5 が
 * {@code AFTER_COMMIT} 境界を要求するのはこの因果のためである。</p>
 *
 * <h2>巻き戻りは実測では再現しなかった（隠さず記録する）</h2>
 * <p>当初は「通知の INSERT 失敗がバッチの送信済みマークごと巻き戻す」と見立てていた。
 * {@link NotificationHelper#notifyAllPreAuthorized} はチャンク失敗を {@code try/catch} で握るが、
 * その下流 {@code NotificationBulkFanoutService} は {@code @Transactional} を持たず呼び出し元の
 * トランザクションに参加するため、「単一トランザクション内の一括 catch は機能しない」既知の形に
 * 見えたからである。しかし {@code ScheduleNotificationTransactionBoundaryIT} で
 * {@code notifications} に CHECK 制約を張って<b>実 DB の INSERT を失敗させ</b>、
 * 是正前のコード（素の {@code @EventListener}）で実測したところ、
 * {@code markAsNotified} は<b>巻き戻らずコミットされた</b>（通知は 0 件のまま）。
 * バルク INSERT が JPA の永続化コンテキストを経由しないため rollback-only が立たず、
 * 一括 catch がこの経路では実際に効いていた、というのが実測の結論である。</p>
 *
 * <p>したがって本件の実害は<b>巻き戻り（ROLLBACK_COUPLED）ではなく順序（因果）</b>であり、
 * 是正の意義もそこにある。{@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Async("event-pool")} へ変更し、通知が業務コミット後にのみ走ることを構造で保証する。
 * <b>見立てと実測が食い違ったときは実測を正とし、見立てのほうを書き換えること</b>
 * （この javadoc の初版は再現していない巻き戻りを断定的に書いていた）。</p>
 *
 * <p>通知種別は {@code SCHEDULE_REMINDER}。文言はイベントに同梱された
 * ローカライズ済みタイトル／本文を使用する（共有＝未回答者向け、個人＝所有者向け）。</p>
 *
 * <p><b>配信＝受信権 統一（関所(1)通知 / (B) レグ取りこぼし根治）</b>:
 * 本リスナーへ届く {@link ReminderNotificationEvent#getRecipientUserIds()} は、いずれの発火元でも
 * 配信母集団として事前認可済みである:
 * <ul>
 *   <li>{@link ScheduleReminderService}（共有予定）→ {@code schedule_attendances} 行由来。
 *       当該行は {@link ScheduleAttendanceService#generateAttendanceRecords} が配信母集団
 *       （ORG={@code resolveOrgDistributionUserIds} の includeSupporters トグル準拠で配下チーム展開／
 *       TEAM={@code findUserIdsByScope}）を materialize した集合であり、出欠募集通知（同
 *       {@code createNotificationPreAuthorized}）と同一母集団。</li>
 *   <li>{@link PersonalScheduleReminderService}（個人予定）→ 予定所有者本人（{@code schedule.userId}）のみ。</li>
 * </ul>
 * よって {@link NotificationHelper#notifyAllPreAuthorized} を用い canView 二重判定を通さない。
 * これにより組織スケジュールのリマインドが配下チームの直属一般メンバーへ誤 deny で届かない
 * (B) レグを根治する（出欠募集通知の {@code createNotificationPreAuthorized} 化と同形）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleReminderNotificationListener {

    /** リマインダー通知の通知種別。 */
    public static final String NOTIFICATION_TYPE = "SCHEDULE_REMINDER";

    /** 通知ソース種別（visibility ガードのソース判定にも使用）。 */
    private static final String SOURCE_TYPE = "SCHEDULE";

    private final NotificationHelper notificationHelper;

    /**
     * リマインダー通知イベントを処理する。
     *
     * @param event リマインダー通知イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。リマインド通知イベントの配信。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReminderNotification(ReminderNotificationEvent event) {
        if (event.getRecipientUserIds() == null || event.getRecipientUserIds().isEmpty()) {
            return;
        }
        try {
            notificationHelper.notifyAllPreAuthorized(
                    event.getRecipientUserIds(),
                    NOTIFICATION_TYPE,
                    NotificationPriority.NORMAL,
                    event.getTitle(),
                    event.getBody(),
                    SOURCE_TYPE,
                    event.getScheduleId(),
                    event.getScopeType(),
                    event.getScopeId(),
                    event.getActionUrl(),
                    null);
            log.info("予定リマインダー通知発火: scheduleId={}, 対象者数={}",
                    event.getScheduleId(), event.getRecipientUserIds().size());
        } catch (Exception e) {
            // 非同期イベント失敗の監査記録（規約上必須）。catch は業務TXの外なので rollback で消えない。
            log.error("予定リマインダー通知の配送に失敗しました: scheduleId={}, 対象者数={}",
                    event.getScheduleId(), event.getRecipientUserIds().size(), e);
        }
    }
}
