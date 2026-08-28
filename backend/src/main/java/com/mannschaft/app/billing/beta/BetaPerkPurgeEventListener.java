package com.mannschaft.app.billing.beta;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.auth.event.WithdrawalCancelledEvent;
import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F20.3 ベータ特典: 退会イベント連動リスナー（設計書 02 §5.1 / 01 §8・AC-19）。
 *
 * <p><b>金型</b>: 同ドメイン同型の {@code BillingPurgeEventListener}（F20.1）。退会猶予との整合（revoke は
 * 終端で復活不可）に合わせ、以下の 3 イベントを購読する:</p>
 * <ul>
 *   <li><b>{@link WithdrawalRequestedEvent}（申請・猶予開始）</b>: <b>明示 no-op</b>。撤回で復活できないため
 *       猶予中は grant を維持する（自動付与バッチ側が退会申請中ユーザーを除外する・02 §3）。</li>
 *   <li><b>{@link WithdrawalCancelledEvent}（撤回）</b>: <b>明示 no-op</b>（grant は維持されたまま）。</li>
 *   <li><b>{@link AccountPurgedEvent}（確定・物理削除）</b>: 撤回窓は閉じており revoke してよい。
 *       {@link BetaGrantService#revokeAllForUser}（{@code REQUIRES_NEW}）へ委譲する。</li>
 * </ul>
 *
 * <p><b>薄いリスナー</b>: 本体の DB 遷移はサービスの {@code REQUIRES_NEW} に委ね、リスナー自身は
 * {@code @Transactional} を持たない（memory {@code feedback_transactional_event_listener_requires_new}）。
 * 例外はイベント基盤へ伝播させない（他ドメインの purge リスナーを妨げない・{@code BillingPurgeEventListener} 前例）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BetaPerkPurgeEventListener {

    private final BetaGrantService betaGrantService;

    /** 退会確定（purge）: USER スコープの有効な特典を全取消＋由来 entitlements 失効（AC-19）。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会確定者のベータ特典と由来 entitlements が失効せず、退会済み利用者に権利が残り続けて GDPR 消去の完了判定も成立しない")
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        try {
            betaGrantService.revokeAllForUser(userId, BetaRevokeReason.WITHDRAWAL);
            log.info("ベータ特典 退会 purge 取消 完了: userId={}", userId);
        } catch (Exception e) {
            // 他ドメインの purge を妨げないため伝播させない（症状は WARN で可視化・手動確認へ）。
            log.warn("ベータ特典 退会 purge 取消に失敗（手動確認要）userId={}", userId, e);
        }
    }

    /** 退会申請（猶予開始）: <b>明示 no-op</b>（撤回で復活できないため猶予中は権利を維持・01 §8）。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "退会フローの明示 no-op であり、宣言を落とすと将来ここに処理が足されたとき猶予中の権利維持判断が黙って飛ぶため常時実行として固定する")
    @EventListener
    public void onWithdrawalRequested(WithdrawalRequestedEvent event) {
        log.debug("ベータ特典: 退会申請は no-op（猶予中は特典を維持）userId={}", event.getUserId());
    }

    /** 退会撤回: <b>明示 no-op</b>（特典は維持されたまま・01 §8）。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "退会撤回フローの明示 no-op であり、宣言を落とすと将来ここに処理が足されたとき撤回時の特典復帰が黙って飛ぶため常時実行として固定する")
    @EventListener
    public void onWithdrawalCancelled(WithdrawalCancelledEvent event) {
        log.debug("ベータ特典: 退会撤回は no-op（特典を維持）userId={}", event.getUserId());
    }
}
