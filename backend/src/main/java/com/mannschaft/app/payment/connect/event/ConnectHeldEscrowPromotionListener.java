package com.mannschaft.app.payment.connect.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.payment.escrow.EscrowLifecycleService;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 受取口座の払出解禁に伴う HELD escrow 昇格リスナー（Issue #2990 L3 / ORDERING_ONLY 是正）。
 *
 * <p>{@code ConnectAccountService#applyAccountUpdated} の業務トランザクション
 * （{@code connect_accounts} の鏡像更新 = {@code payouts_enabled} を true にする UPDATE）が
 * commit された<b>後</b>に、当該口座を payee とする {@link EscrowStatus#HELD} escrow を順に昇格する
 * （設計書 02 §5.2）。</p>
 *
 * <h2>是正前の欠陥: REQUIRES_NEW が未 commit の鏡像を読めず、昇格が常に空振りしていた</h2>
 * <p>是正前は {@code applyAccountUpdated} の {@code @Transactional} の内側から
 * {@code promoteHeldEscrowsForPayee} → {@link EscrowLifecycleService#promoteHeldEscrow} を呼んでいた。
 * {@code promoteHeldEscrow} は {@code REQUIRES_NEW}（＝別トランザクション・別コネクション）であり、
 * その内部で <b>payee 口座を読み直して {@code payouts_enabled} を検証する</b>:</p>
 *
 * <pre>{@code
 * if (payee == null || !Boolean.TRUE.equals(payee.getPayoutsEnabled())) {
 *     log.warn("HELD 昇格スキップ: payee 口座が解決不能/未 READY: ...");
 *     return false;
 * }
 * }</pre>
 *
 * <p>ところが {@code payouts_enabled = true} を書いた外側の業務TXは<b>まだ commit していない</b>。
 * READ COMMITTED の下では {@code REQUIRES_NEW} の内側から未 commit の更新は見えないため、
 * 読み直した値は<b>常に旧値（false）</b>であり、昇格は毎回このガードで {@code false} を返して
 * 空振りしていた。すなわちこれは「通知の順序が入れ替わる」だけの問題ではなく、
 * <b>HELD escrow の自動昇格および札主への決済確認依頼通知が丸ごと発火しない機能欠損</b>であった。
 * 鏡像更新の commit 後に起動位置を移すことで、読み直しが新値を見るようになり本来の設計どおり昇格する。</p>
 *
 * <h2>@Async を付けない理由</h2>
 * <p>昇格は Stripe の PaymentIntent 作成を伴う金銭処理であり、失敗を確実に ERROR ログへ残したい。
 * {@code account.updated} Webhook は Stripe 側のリトライ対象でもあるため、
 * Webhook 応答スレッド上で（commit 後に）同期実行し、処理の完了を応答前に確定させる。
 * 各 escrow は {@link EscrowLifecycleService#promoteHeldEscrow}（{@code REQUIRES_NEW} ＋行ロック＋冪等）で
 * 個別に処理し、1 件の失敗は握りつぶさず ERROR ログに残して他件の昇格を継続する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectHeldEscrowPromotionListener {

    private final EscrowTransactionRepository escrowTransactionRepository;
    private final EscrowLifecycleService escrowLifecycleService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "受取口座の onboarding 完了に伴う HELD escrow 昇格は金銭処理の必須後段であり、"
                    + "止めると札主への決済確認依頼が発火せず謝礼が滞留するため常時実行する")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConnectPayoutsEnabled(ConnectPayoutsEnabledEvent event) {
        UUID payeeAccountId = event.connectAccountId();
        List<EscrowTransactionEntity> held = escrowTransactionRepository
                .findByPayeeConnectAccountIdAndStatus(payeeAccountId, EscrowStatus.HELD);
        if (held.isEmpty()) {
            return;
        }
        log.info("HELD escrow の昇格を開始: payeeAccountId={}, 対象={}件", payeeAccountId, held.size());
        int promoted = 0;
        for (EscrowTransactionEntity escrow : held) {
            try {
                if (escrowLifecycleService.promoteHeldEscrow(escrow.getId())) {
                    promoted++;
                }
            } catch (RuntimeException ex) {
                log.error("HELD escrow の昇格に失敗（他件は継続）: escrowId={}, reason={}",
                        escrow.getId(), ex.getMessage(), ex);
            }
        }
        log.info("HELD escrow の昇格完了: payeeAccountId={}, 昇格={}/{}件", payeeAccountId, promoted, held.size());
    }
}
