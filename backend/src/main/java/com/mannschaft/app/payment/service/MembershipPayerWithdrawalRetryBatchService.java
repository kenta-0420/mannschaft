package com.mannschaft.app.payment.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 柱③-B（CMP-260901-1538）PR-4: 払い手退会に伴う期末解約の<b>夜次再試行バッチ</b>
 * （設計書 {@code billing_payer_handover_design.md} §6.1 (2)）。
 *
 * <h2>なぜこのクラスが要るのか（PR-3 のリリース依存の解消）</h2>
 * <p>退会イベントは永続化されない Spring のインメモリイベントであり、ハンドラは共有 {@code event-pool} 上の
 * 非同期処理である。Stripe 失敗・投入拒否・commit 直後のプロセス停止では処理そのものが失われる。
 * PR-3 はそれに備えて<b>処理状態</b>（{@code membership_payer_withdrawal_cancellations} の非終端3値）と
 * <b>取りこぼしの再構築経路</b>（{@code findWithdrawalCancelBacklog()}）を用意したが、
 * <b>それを拾う主体を持たなかった</b>——「対象は DB に残るが、誰も拾わない」状態である。
 * 本バッチがその駆動主体であり、これが着地して初めて<b>失敗した期末解約が自動で回復する</b>。</p>
 *
 * <h2>2経路の union / dedup（PR-3 からの申し送り）</h2>
 * <p>抽出は {@code MembershipSubscriptionService#findWithdrawalCancelRetryPayerUserIds()} が
 * 「非終端の作業行」と「backlog」を<b>払い手単位で union / dedup</b> して返す。
 * PR-3 の Javadoc は両者が「定義上互いに素」としているが、backlog の照会は複数の独立クエリの
 * 組み合わせでトランザクションを張っておらず、照会の間に作業行が作られれば重なりうる。
 * 「同一時点のスナップショットとしては互いに素」＝「常に互いに素」ではないため、dedup は必須である。</p>
 *
 * <h2>トランザクション設計</h2>
 * <p><b>{@code @Transactional} は付けない。</b> 払い手ごとの処理は
 * {@code MembershipPayerWithdrawalRunner} が契約単位の独立トランザクション（{@code REQUIRES_NEW}）へ
 * 分解する。バッチ全体を1トランザクションで包むと、1件の DB 失敗で成功した全件の DB 変更が巻き戻る一方、
 * 先に成功した Stripe の {@code cancel_at_period_end=true} は戻らない（PR-3 の検分1巡目 P1-2 と同じ穴）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MembershipPayerWithdrawalRetryBatchService {

    private final MembershipSubscriptionService membershipSubscriptionService;

    /**
     * 期末解約（と、退会取消に伴うその解除）のやり残しを再試行する。
     *
     * <p><b>解約側と解除側は別パスとして順に流す。</b> 処理の向きが逆であり、片方の障害で
     * もう片方をスキップしてはならない（解除側を落とすと「退会を取り消したのに期末で終了する」が残り、
     * 解約側を落とすと「退会したのに課金が続く」が残る。どちらも利用者の金銭に直結する）。
     * 解約側を先に流すのは、ロック順序の正準（{@code users} → {@code membership_subscriptions} →
     * {@code membership_payer_withdrawal_cancellations}）を両パスが共有しており、
     * パス間の順序を固定しておくほうが競合の再現性が高いためである。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_BILLING_PAYMENT_ENABLED",
            reason = "再試行対象は作業行の非終端状態と退会状態そのものから毎回導出するため、閉栓中にスキップしても失われない。閉栓中は Stripe への解約発行自体を止めたい局面でもあり、再開後の最初の実行が同じ対象を拾い直す")
    @BatchEndpoint(name = "membership-payer-withdrawal-retry",
            description = "払い手退会に伴う期末解約／その解除のやり残しを再試行する（柱③-B・日次）")
    @Scheduled(cron = "${mannschaft.payment.payer-withdrawal-retry.cron:0 20 3 * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(name = "membership_payer_withdrawal_retry",
            lockAtMostFor = "PT60M", lockAtLeastFor = "PT1M")
    public void runWithdrawalCancelRetry() {
        retryCancels();
        retryRestores();
    }

    /** 解約側: 非終端の作業行（PENDING/FAILED）と backlog の union（払い手単位で dedup 済み）。 */
    private void retryCancels() {
        List<Long> payerUserIds;
        try {
            payerUserIds = membershipSubscriptionService.findWithdrawalCancelRetryPayerUserIds();
        } catch (Exception e) {
            log.error("払い手退会の期末解約再試行: 対象払い手の抽出に失敗しました", e);
            return;
        }
        int failed = 0;
        // 抽出側も dedup 済みだが、駆動側でも必ず畳む。抽出は2経路の union であり、
        // 片方の実装変更で重複が漏れ出したときに「同じ払い手へ二重に Stripe を発行する」形へ
        // 静かに退行しうる。ここで畳んでおけば、その退行は「取りこぼし」ではなく無害な冗長で済む。
        for (Long payerUserId : new LinkedHashSet<>(payerUserIds)) {
            try {
                membershipSubscriptionService.cancelAllForPayerOnWithdrawal(payerUserId);
            } catch (Exception e) {
                // catch は必ず tx の外側（このループ）で行う。Runner の内側で catch すると
                // rollback-only のトランザクションで記録ごと消える。
                failed++;
                log.error("払い手退会の期末解約再試行: 払い手 {} の再試行に失敗しました", payerUserId, e);
            }
        }
        if (!payerUserIds.isEmpty()) {
            log.warn("払い手退会の期末解約再試行: 対象払い手={}, 失敗={}"
                    + "（対象が常時0件でない場合は退会イベント経路の失敗を調査すること）",
                    payerUserIds.size(), failed);
        }
    }

    /** 解除側: {@code RESTORING} のまま止まった行（Stripe 解除の確定が未了）。 */
    private void retryRestores() {
        List<Long> payerUserIds;
        try {
            payerUserIds = membershipSubscriptionService.findWithdrawalRestoreRetryPayerUserIds();
        } catch (Exception e) {
            log.error("払い手退会取消の解除再試行: 対象払い手の抽出に失敗しました", e);
            return;
        }
        int failed = 0;
        for (Long payerUserId : new LinkedHashSet<>(payerUserIds)) {
            try {
                membershipSubscriptionService.restoreAllForPayerOnWithdrawalCancelled(payerUserId);
            } catch (Exception e) {
                failed++;
                log.error("払い手退会取消の解除再試行: 払い手 {} の再試行に失敗しました", payerUserId, e);
            }
        }
        if (!payerUserIds.isEmpty()) {
            log.warn("払い手退会取消の解除再試行: 対象払い手={}, 失敗={}", payerUserIds.size(), failed);
        }
    }
}
