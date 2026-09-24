package com.mannschaft.app.receipt.listener;

import com.mannschaft.app.advertising.event.AdInvoicePaidEvent;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.credit.event.NotificationCreditPurchasePaidEvent;
import com.mannschaft.app.receipt.PlatformReceiptSourceResolver;
import com.mannschaft.app.receipt.ReceiptSourceRef;
import com.mannschaft.app.receipt.ReceiptSourceType;
import com.mannschaft.app.receipt.dto.PlatformReceiptIssueCommand;
import com.mannschaft.app.receipt.service.PlatformReceiptIssueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 入金確定を受けて運営領収書を発行するリスナー（F08.12 §5.2）。
 *
 * <h2>トランザクション境界</h2>
 * <p>{@code AFTER_COMMIT} で受け、発行自体は
 * {@link PlatformReceiptIssueService#issueFor}（{@code REQUIRES_NEW}）で独立させる。
 * <b>領収書の発行失敗が入金確定をロールバックしてはならない。</b></p>
 *
 * <h2>失敗したときに何が起きるか（握りつぶさない）</h2>
 * <p>失敗は WARN ログに残し、領収書は<b>未発行のまま</b>にする。運用は
 * {@code POST /api/v1/system-admin/receipts/backfill} で手動リカバリする。
 * 例外を飲んで「成功したように見せる」ことはしない。未発行として観測可能な状態にすることが
 * 目的である。</p>
 */
@Slf4j
@Component
public class PlatformReceiptIssueListener {

    private final PlatformReceiptIssueService issueService;
    private final Map<ReceiptSourceType, PlatformReceiptSourceResolver> resolvers =
            new EnumMap<>(ReceiptSourceType.class);

    public PlatformReceiptIssueListener(PlatformReceiptIssueService issueService,
                                        List<PlatformReceiptSourceResolver> sourceResolvers) {
        this.issueService = issueService;
        for (PlatformReceiptSourceResolver resolver : sourceResolvers) {
            this.resolvers.put(resolver.supportedSourceType(), resolver);
        }
    }

    /** 広告費の入金確定。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS, reason = "止めると入金済みの広告費に対する領収書が欠落する。入金確定（ad_invoices.paid_at）は既にコミット済みで巻き戻らない一方、本イベントは AFTER_COMMIT で一度きり配送されるため、停止期間中の入金は再送されず二度と拾えない。過去分を走査し直す常設バッチは存在しない（手動 backfill は第二陣で未実装）。領収書は電子帳簿保存法の保存対象であり欠落は法的な瑕疵になる。対応する gate_key も無く、そもそも止める手段が無い。")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAdInvoicePaid(AdInvoicePaidEvent event) {
        issueQuietly(ReceiptSourceType.AD_INVOICE, ReceiptSourceRef.of(event.adInvoiceId()));
    }

    /** 通知プリペイドクレジット購入の入金確定。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS, reason = "止めると入金済みの通知クレジット購入に対する領収書が欠落する。決済は Stripe 側で完了し残高も加算済みであり巻き戻らない一方、本イベントは AFTER_COMMIT で一度きり配送されるため、停止期間中の購入は再送されず二度と拾えない。過去分を走査し直す常設バッチは存在しない（手動 backfill は第二陣で未実装）。領収書は電子帳簿保存法の保存対象であり欠落は法的な瑕疵になる。対応する gate_key も無く、そもそも止める手段が無い。")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreditPurchasePaid(NotificationCreditPurchasePaidEvent event) {
        issueQuietly(ReceiptSourceType.NOTIFICATION_CREDIT_PURCHASE,
                ReceiptSourceRef.of(event.purchaseId()));
    }

    private void issueQuietly(ReceiptSourceType sourceType, ReceiptSourceRef sourceRef) {
        try {
            PlatformReceiptSourceResolver resolver = resolvers.get(sourceType);
            if (resolver == null) {
                log.warn("運営領収書の発行内容を解決できる実装がない sourceType={} sourceRef={}",
                        sourceType, sourceRef);
                return;
            }
            Optional<PlatformReceiptIssueCommand> command = resolver.resolve(sourceRef);
            if (command.isEmpty()) {
                log.warn("運営領収書の元データが解決できないため未発行のままにする sourceType={} sourceRef={}",
                        sourceType, sourceRef);
                return;
            }
            PlatformReceiptIssueService.IssueResult result = issueService.issueFor(command.get());
            if (result.newlyIssued()) {
                log.info("運営領収書を発行した receiptNumber={} sourceType={} sourceRef={}",
                        result.receipt().getReceiptNumber(), sourceType, sourceRef);
            }
        } catch (RuntimeException e) {
            // 入金確定は既にコミット済みである。ここで再スローしても入金は戻らず、
            // Stripe への応答が落ちて無用な再送を招くだけなので、未発行として記録する。
            log.warn("運営領収書の発行に失敗した。backfill による手動リカバリが必要である "
                    + "sourceType={} sourceRef={}", sourceType, sourceRef, e);
        }
    }
}
