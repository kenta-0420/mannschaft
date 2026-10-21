package com.mannschaft.app.reservation.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.reservation.service.ReservationWaitlistNotifyRunner;
import com.mannschaft.app.reservation.service.ReservationWaitlistNotifyRunner.Target;
import com.mannschaft.app.reservation.service.ReservationWaitlistNotifyRunner.Targets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * 予約枠の空き復帰時に、キャンセル待ち登録者へ一斉通知するリスナー（F03.4.5 §6.1 / Issue #2997 G3）。
 *
 * <p>{@link ReservationSlotReopenedEvent}（満席→空きへ転じた瞬間に
 * {@code ReservationSlotService.decrementAndReopen} が発行）を購読する。</p>
 *
 * <p><b>トランザクション設計:</b> 空き復帰は確定 TX（キャンセル）の副作用であるため
 * {@code @TransactionalEventListener(AFTER_COMMIT)} で購読し、{@code @Async("event-pool")} で
 * キャンセル応答をブロックしない。本リスナー自身には {@code @Transactional} を付けない
 * （登録時バリデーション失敗で全 SpringBootTest 巻き添えを回避）。DB の読み書きは
 * {@link ReservationWaitlistNotifyRunner} の独立トランザクションで行う。</p>
 *
 * <h2>配送の流れ（マスター裁可 M1・M2）</h2>
 * <ol>
 *   <li>{@link ReservationWaitlistNotifyRunner#findTargets} で枠の空きを再確認し、通知候補の行（entryId・userId・prev）を読む。</li>
 *   <li>行ごとに、{@link ReservationWaitlistNotifyRunner#claim}（条件付き UPDATE。WAITING かつ未通知または抑止期限以前）で
 *       {@code notified_at} を取る。取れなければ他の配送が担当済み、または抑止期限内なのでその行は送らない。</li>
 *   <li>取れた行へ {@code NotificationDeliveryRunner#sendOne}（{@code REQUIRES_NEW}）で配送する。</li>
 *   <li><b>例外のときだけ</b> {@link ReservationWaitlistNotifyRunner#release}（{@code WHERE notified_at = claimedAt}）で prev に戻す
 *       （次の空き復帰で再通知される）。成功と deny（{@code VISIBILITY_DENIED}）は印を保持する（deny は再送しない）。</li>
 *   <li>戻す処理が失敗しても ERROR ログのみで、例外は外に出さない（印が残るので再通知されない）。</li>
 * </ol>
 *
 * <p>1 人の失敗（組み立て・claim・配送・戻しのどれ）も行ごとの内側 try で隔離し、他の行に波及させない。</p>
 *
 * <h2>既知の限界</h2>
 * <ul>
 *   <li>M1: claim の後でプロセスが落ちると、その行はこの回の通知を失う（印が立ったまま配送されない）。</li>
 *   <li>M9: event-pool が飽和して投入が拒否された回の通知は失われる（キャンセル自体はコミット済み）。
 *       次の空き復帰で再通知される。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationWaitlistNotificationEventListener {

    /** 空き通知の通知種別（HIGH・"RESERVATION" カテゴリ）。 */
    static final String NOTIFICATION_TYPE = "RESERVATION_WAITLIST_OPENING";

    /** 通知 sourceType（F00 visibility / 受信権の判定キー）。 */
    private static final String SOURCE_TYPE = "RESERVATION";

    private final ReservationWaitlistNotifyRunner notifyRunner;
    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    private final Clock clock;

    /**
     * 枠の空き復帰イベントを受信し、当該枠の WAITING 登録者へ一斉通知する。
     *
     * @param event 空き復帰イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。空き発生時のキャンセル待ち通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSlotReopened(ReservationSlotReopenedEvent event) {
        Targets found;
        // Issue #2526 検討済み・変更しない: 比較相手は notifiedAt であり、notifiedAt 自体も本リスナーが
        // LocalDateTime.now(clock) から書く。業務ローカル時刻（slot_date/start_time）は一切絡まない UTC Clock 同士の
        // 自己完結した比較のため、systemDefault へ変えると逆にズレて壊れる。一律置換禁止。
        // DATETIME(6) で往復できる精度（マイクロ秒）に丸める（release の照合キーにするため）。
        LocalDateTime claimedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime suppressBefore = ReservationWaitlistNotifyRunner.suppressBefore(claimedAt);
        try {
            found = notifyRunner.findTargets(event.getSlotId(), suppressBefore);
        } catch (Exception e) {
            // AFTER_COMMIT の副作用失敗。確定 TX は既にコミット済みのため波及させず、正直に記録する。
            log.error("キャンセル待ち空き通知の対象取得に失敗しました: teamId={}, slotId={}",
                    event.getTeamId(), event.getSlotId(), e);
            return;
        }
        if (found == null) {
            return;
        }

        int notified = 0;
        for (Target target : found.targets()) {
            if (notifyOne(event, found, target, claimedAt, suppressBefore)) {
                notified++;
            }
        }
        log.info("キャンセル待ち空き通知: teamId={}, slotId={}, 対象{}件中 {}件送出",
                event.getTeamId(), event.getSlotId(), found.targets().size(), notified);
    }

    /**
     * 1 行を claim して配送する。送出した（deny を除く）場合 {@code true}。
     *
     * <p>claim できた後の例外は prev に戻す。行ごとの失敗は呼び出し元へ伝播させない。</p>
     */
    private boolean notifyOne(ReservationSlotReopenedEvent event, Targets found, Target target,
                              LocalDateTime claimedAt, LocalDateTime suppressBefore) {
        try {
            // 配送の前に印を先取りする。取れなければ他の配送が担当済み、または抑止期限内なので送らない。
            if (!notifyRunner.claim(target.entryId(), claimedAt, suppressBefore)) {
                return false;
            }
        } catch (Exception e) {
            log.error("キャンセル待ち通知の印の先取りに失敗したため配送しません: entryId={}, slotId={}, userId={}",
                    target.entryId(), event.getSlotId(), target.userId(), e);
            return false;
        }

        try {
            NotificationDeliveryRequest request = buildRequest(event, found, target);
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。印は保持する（戻さない）。
                log.warn("キャンセル待ち空き通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                        request.recipientUserId(), request.notificationType(),
                        request.sourceType(), request.sourceId());
                return false;
            }
            return true;
        } catch (Exception e) {
            // 1 件の失敗が他の宛先を巻き込まないよう行単位で隔離して記録し、印を prev に戻す。
            log.error("キャンセル待ち空き通知の送出に失敗しました（印を戻して次の空き復帰で再通知）: entryId={}, slotId={}, userId={}",
                    target.entryId(), event.getSlotId(), target.userId(), e);
            releaseClaim(event, target, claimedAt);
            return false;
        }
    }

    /** 配送が例外で失敗した行の印を prev に戻す。戻しの失敗は ERROR ログのみ（例外は外に出さない）。 */
    private void releaseClaim(ReservationSlotReopenedEvent event, Target target, LocalDateTime claimedAt) {
        try {
            notifyRunner.release(target.entryId(), claimedAt, target.prevNotifiedAt());
        } catch (Exception e) {
            log.error("キャンセル待ち通知の印を戻せませんでした（配送失敗だが再通知されない）: entryId={}, slotId={}, userId={}",
                    target.entryId(), event.getSlotId(), target.userId(), e);
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(ReservationSlotReopenedEvent event, Targets found, Target target) {
        // UserLocaleCache は TTL 5分・件数上限つき LRU（D-5: 予約ドメインから auth リポジトリへ
        // 直接依存しない・common.i18n 配下の共有サービス経由に限定する）。
        Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(target.userId()));
        String slotTitle = found.slotTitle() != null ? found.slotTitle()
                : messageSource.getMessage("notification.reservation.common.defaultSlotTitle", null, "ご予約", locale);
        String title = messageSource.getMessage(
                "notification.reservation.waitlistOpening.title", null, "キャンセルが出ました", locale);
        String body = messageSource.getMessage(
                "notification.reservation.waitlistOpening.body",
                new Object[]{slotTitle},
                "「" + slotTitle + "」に空きが出ました。お早めにご予約ください。",
                locale);
        return new NotificationDeliveryRequest(
                target.userId(),
                NOTIFICATION_TYPE,
                NotificationPriority.HIGH,
                title,
                body,
                SOURCE_TYPE,
                event.getSlotId(),
                NotificationScopeType.TEAM,
                event.getTeamId(),
                "/teams/" + event.getTeamId() + "/reservations",
                null);
    }
}
