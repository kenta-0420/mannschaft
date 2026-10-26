package com.mannschaft.app.family.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.family.event.EventEndReminderDueEvent;
import com.mannschaft.app.family.service.EventEndReminderBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 解散通知リマインドの配送リスナー（Issue #2990 L6 TX_NOTIFY_BARE 是正）。
 *
 * <h2>是正前の欠陥 — 何が巻き戻っていたか</h2>
 * <p>{@code EventEndReminderBatchService#runEndReminderCheck} は<b>バッチ全体を覆う単一の
 * {@code @Transactional}</b> であり（{@code @Scheduled} 入口なので Spring プロキシを通り、
 * この宣言は実効である。自己呼び出しによる失効は無い）、その内側から
 * {@code sendReminderNotification} が {@code notificationService.createNotification} と
 * {@code dispatchService.dispatch} を直接呼んでいた。{@code createNotification} は自身も
 * {@code @Transactional} を持つが伝播は既定の {@code REQUIRED} であり、
 * バッチのトランザクションに参加する。</p>
 *
 * <p>ここで実 DB エラー（{@code notifications} への INSERT が制約違反で落ちる等）が起きると、
 * Spring はバッチのトランザクションを <b>rollback-only</b> にマークする。
 * {@code runEndReminderCheck} のループには {@code catch (Exception e)} があるため例外自体は握られ、
 * バッチは残りのイベントを処理して正常終了したかのように見える。しかし commit の瞬間に
 * {@code UnexpectedRollbackException} となり、<b>その回のバッチが書いた
 * {@code organizer_reminder_sent_count} のインクリメントが全イベントぶん巻き戻る</b>。</p>
 *
 * <p>結果として、次の 5 分後の実行で同じイベント群がふたたび全件リマインド対象となり、
 * 主催者へ 1 回目のリマインドが繰り返し飛び続ける（3 回目まで進めば主催者だけでなく
 * チームの全 ADMIN へ URGENT 通知が飛ぶ）。冪等性の担保がカウンタ 1 本に載っているため、
 * 通知 1 件の失敗が<b>通知の無限リピート</b>という逆向きの障害に化ける経路だった。</p>
 *
 * <h2>是正後</h2>
 * <p>業務トランザクションの内側ではカウンタのインクリメントと {@link EventEndReminderDueEvent}
 * の publish までを行い、通知の実配送は本リスナーが {@code AFTER_COMMIT} +
 * {@code @Async("event-pool")} で受けて
 * {@link EventEndReminderBatchService#deliverReminder(Long, int)} を呼ぶ。
 * 配送が失敗してもカウンタは既にコミット済みなので、同じ段階のリマインドが再送されることはない
 * （冪等性は保たれ、失敗は ERROR ログで追える）。</p>
 *
 * <h2>{@code @ConditionalOnProperty} を揃える理由</h2>
 * <p>発行元 {@link EventEndReminderBatchService} が
 * {@code care.dismissal-reminder.enabled=true} のときだけ Bean 化される。本リスナーは同サービスを
 * 注入するため、同じ条件を付けないと機能無効時に依存解決できず起動が落ちる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "care.dismissal-reminder.enabled", havingValue = "true")
public class EventEndReminderDeliveryListener {

    private final EventEndReminderBatchService eventEndReminderBatchService;

    /**
     * 解散リマインドを業務コミット後に配送する。
     *
     * @param event 配送要求イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
            gateKeys = "FEATURE_FAMILY_CARE_ENABLED",
            reason = "落ちるのは解散リマインドの送信だけでイベント本体の状態は書き換えない。"
                    + "発行元バッチが同じ gate_key で SKIP_WHEN_DISABLED を宣言しており、"
                    + "機能停止中はそもそもイベントが発行されない。停止中に取りこぼした分を"
                    + "後から再生する必要も無い（クエリ側の鮮度下限が終了から 24 時間で切る）")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventEndReminderDue(EventEndReminderDueEvent event) {
        if (event.eventId() == null) {
            return;
        }
        try {
            eventEndReminderBatchService.deliverReminder(event.eventId(), event.stage());
        } catch (Exception e) {
            // 非同期イベント失敗の監査記録（規約上必須）。業務TX外なので rollback で消えない。
            // カウンタは既にコミット済みのため、この失敗で同じ段階が再送されることはない。
            log.error("解散通知リマインドの配送に失敗しました: eventId={}, stage={}",
                    event.eventId(), event.stage(), e);
        }
    }
}
