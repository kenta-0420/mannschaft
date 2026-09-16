package com.mannschaft.app.billing;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Billing Center PR6a: 停止窓の回収を<b>自動で走らせる駆動主体</b>（D8・AC-78〜AC-82）。
 *
 * <h2>なぜこのクラスが要るのか</h2>
 * <p>{@link BillingContractOperationRecoveryService} は「1周の走査」と「1件の回収」を実装するが、
 * それを呼ぶ者がいなければ回収は一度も起きない。D1（tx1 commit → Stripe → tx2）は既存の
 * 「期末の {@code customer.subscription.deleted} による自己修復」を外しているため、駆動主体が無い
 * ままでは {@code CALLING_STRIPE} と pointer が<b>永久残留</b>し、その契約は利用者から二度と
 * 操作できなくなる。{@code customer.subscription.updated}（AC-83）はもう一つの入口だが、
 * 停止窓(a)（Stripe をそもそも呼べていない）は Stripe から何も飛んでこないため、
 * <b>定期走査だけがその受け皿である</b>。</p>
 *
 * <h2>トランザクション設計</h2>
 * <p><b>本クラスに {@code @Transactional} を付けない。</b> 走査全体を1トランザクションで包むと、
 * 1件の失敗で rollback-only が残り、成功した全件が最後のコミットで巻き戻る
 * （{@code ShiftCleanupBatchService} が踏んだ事故）。トランザクションは
 * {@link BillingContractOperationRecoveryService} が1件ごとに張る。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingContractOperationRecoveryBatchService {

    private final BillingContractOperationRecoveryService recoveryService;

    /**
     * stale な operation を回収する（AC-78/79/80/81/82/84）。
     *
     * <p>実行間隔（5分）を stale しきい値（{@link BillingContractOperationRecoveryService
     * #DEFAULT_STALE_THRESHOLD}）と揃えているのは、停止窓に落ちた operation の滞留を
     * 「しきい値＋1周」以内に収めるためである。1周の件数は走査側で上限が置かれており
     * （{@code MAX_SCAN_BATCH}）、あふれた分は次周が古い順に拾い直す（取りこぼさない）。</p>
     *
     * <p>{@code lockAtMostFor} を実行間隔より十分長く取っているのは、ロックが実行途中で失効して
     * 次回起動と並走するのを防ぐためである（番人 {@code ScheduledBatchGuardTest} のルール4）。
     * 仮に並走しても status CAS の更新件数で勝者が一意に決まるため二重には効かない（AC-82）。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_BILLING_PAYMENT_ENABLED",
            reason = "決済を閉栓している間は Stripe を呼ぶ operation が新たに起票されず、停止窓も新規には生じない。回収対象は「status と updated_at」という DB の状態のみから毎回導出されるため、再開後の最初の実行が滞留ごと拾い直す")
    @BatchEndpoint(name = "billing-contract-operation-recovery",
            description = "契約操作 Saga の停止窓（CREATED / CALLING_STRIPE の滞留）を Stripe と再照合して回収する（PR6a・5分毎）")
    @Scheduled(cron = "${mannschaft.billing.operation-recovery.cron:0 */5 * * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(name = "billing_contract_operation_recovery",
            lockAtMostFor = "PT20M", lockAtLeastFor = "PT30S")
    public void runOperationRecovery() {
        BillingContractOperationRecoveryService.RecoveryOutcome outcome =
                recoveryService.recoverStaleOperations();
        if (outcome.scanned() == 0) {
            log.debug("PR6a 停止窓の回収: 対象なし");
            return;
        }
        log.info("PR6a 停止窓の回収バッチ: scanned={}, cancelled={}, applied={}, quarantined={}",
                outcome.scanned(), outcome.cancelledStale(),
                outcome.appliedFromStripe(), outcome.quarantined());
    }
}
