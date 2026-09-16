package com.mannschaft.app.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Billing Center PR6b-1 第7隊: upgrade の確定（B群 AC-32〜45）。
 *
 * <h2>E1F・E6' の要点</h2>
 * <p>upgrade の operation は決着まで {@code CALLING_STRIPE} のまま pointer を保持する（E1F）。
 * 確定の唯一の主体は {@code invoice.paid}（成功時）/ {@code invoice.payment_failed} /
 * {@code invoice.voided} / {@code customer.subscription.pending_update_expired}（失敗時）であり、
 * API 応答や回収は確定しない（E6'）。</p>
 *
 * <h2>原子性の実現方法（AC-37〜41）</h2>
 * <p>確定は {@link BillingContractOperationSagaService#applyAndFinalize} /
 * {@link BillingContractOperationSagaService#failAndRelease}（PR6a 資産）にそのまま乗せる。
 * どちらも既定の {@code PROPAGATION_REQUIRED} で呼び出し元の webhook トランザクションへ<b>合流</b>する
 * ため、change 行の更新・権利（契約）の切替・operation の terminal 化・pointer 解放が
 * ひとつの物理トランザクションとして成否する。</p>
 *
 * <h2>invoice 先着（AC-42/AC-43）</h2>
 * <p>change 行作成時点では {@code stripe_invoice_ref} が NULL のことがある。invoice webhook が
 * Stripe への同期応答より先に届いた場合、{@code resolveByInvoice} が
 * {@link BillingPaymentGateway#findOperationIdOnSubscription} で operationId を逆引きし、
 * 見つかった change へ invoice ref を<b>一度だけ</b> bind する（bind は
 * {@link #confirmPaid} の反映処理内で {@code saveAndFlush} し、{@code uk_bcc_invoice} の
 * 一意制約違反を早期に検出することで AC-41 の巻き戻りを成立させる）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingPlanChangeConfirmationService {

    /** 支払い待ち・追加認証待ち＝まだ確定していない（E6'）。 */
    private static final Set<BillingContractChangeStatus> IN_FLIGHT = EnumSet.of(
            BillingContractChangeStatus.PENDING_PAYMENT, BillingContractChangeStatus.REQUIRES_ACTION);

    private final BillingContractChangeRepository changeRepository;
    private final BillingContractRepository contractRepository;
    private final BillingContractOperationSagaService sagaService;
    private final BillingPaymentGateway billingPaymentGateway;

    /**
     * invoice に対応する upgrade の change を解決する（AC-42）。
     *
     * <h2>解決順序（AC-41 の decoy 検体が根拠）</h2>
     * <p><b>subscription 経由（operationId 逆引き）を先に試す</b>。{@code stripe_invoice_ref} は
     * グローバル一意（{@code uk_bcc_invoice}）だが、<b>まだ bind されていない別契約</b>が
     * たまたま同じ invoice ref を先に握っている状態（bind の競合・AC-41 の decoy）では、
     * 直接引きが<b>無関係な契約の行</b>を返してしまう。operationId は
     * {@code metadata.billingOperationId} 由来で契約に固有なので、これを先に試すことで
     * 「この event の subscription が指す契約の change」だけを確実に掴む。
     * subscription 逆引きが使えない（未実装・スタブなし）場合のみ、直接引きへフォールバックする
     * （AC-37〜39: 既に bind 済みの通常経路）。</p>
     *
     * <p>返す change の状態は問わない（terminal でも返す）。実際に確定してよいかは
     * {@link #confirmPaid}/{@link #confirmFailed} 側の {@link #IN_FLIGHT} 判定に委ねる
     * （すでに確定済みの再送を冪等に無視するため）。</p>
     *
     * @param invoiceRef      Stripe Invoice ID（{@code null} なら空を返す）
     * @param subscriptionRef Stripe Subscription ID（逆引きキー。{@code null} なら bind 経路を試みない）
     * @return upgrade の差額請求だと判定できた change
     */
    public Optional<BillingContractChangeEntity> resolveByInvoice(String invoiceRef, String subscriptionRef) {
        if (invoiceRef == null || invoiceRef.isBlank()) {
            return Optional.empty();
        }
        if (subscriptionRef != null && !subscriptionRef.isBlank()) {
            Optional<BillingContractChangeEntity> viaSubscription = resolveViaSubscription(subscriptionRef);
            if (viaSubscription.isPresent()) {
                return viaSubscription;
            }
        }
        return changeRepository.findByStripeInvoiceRefAndDeletedAtIsNull(invoiceRef);
    }

    /**
     * Stripe を参照できない一時失敗は「見送る」（PR6a
     * {@code BillingContractOperationRecoveryService#inspectStripe} と同じ流儀）。ここで例外を
     * 外へ出すと、この invoice が upgrade と無関係でも invoice webhook 全体が壊れてしまう
     * （AC-140/141 の回帰）。
     */
    private Optional<BillingContractChangeEntity> resolveViaSubscription(String subscriptionRef) {
        try {
            return billingPaymentGateway.findOperationIdOnSubscription(subscriptionRef)
                    .flatMap(changeRepository::findByOperationIdAndDeletedAtIsNull);
        } catch (RuntimeException e) {
            log.warn("PR6b-1: Stripe subscription 参照に失敗したため invoice ref 直接引きへ見送る: "
                    + "subscriptionRef={}", subscriptionRef, e);
            return Optional.empty();
        }
    }

    /**
     * {@code customer.subscription.pending_update_expired} 用に、metadata の operationId から
     * 直接 change を解決する（AC-40。invoice を経由しないため {@link #resolveByInvoice} は使えない）。
     */
    public Optional<BillingContractChangeEntity> resolveByOperationId(UUID operationId) {
        if (operationId == null) {
            return Optional.empty();
        }
        return changeRepository.findByOperationIdAndDeletedAtIsNull(operationId)
                .filter(change -> IN_FLIGHT.contains(change.getStatus()));
    }

    /**
     * paid 確定（AC-37/AC-42）。change を {@code APPLIED} にし、契約の権利（planKey/band）を
     * 切り替え、operation を {@code APPLIED}、pointer を削除する——すべて同一トランザクション。
     *
     * <p>すでに確定済み（{@link #IN_FLIGHT} でない）なら冪等 no-op（AC-42 の二度目の再送）。</p>
     *
     * @param change     {@link #resolveByInvoice} が返した change
     * @param invoiceRef bind すべき invoice ref（すでに bind 済みなら変化しない）
     */
    public void confirmPaid(BillingContractChangeEntity change, String invoiceRef) {
        if (!IN_FLIGHT.contains(change.getStatus())) {
            log.info("PR6b-1: 既に確定済みの change への paid 再送を無視する: changeId={}, status={}",
                    change.getId(), change.getStatus());
            return;
        }
        UUID operationId = change.getOperationId();
        sagaService.applyAndFinalize(operationId, () -> {
            BillingContractChangeEntity locked = changeRepository
                    .findByOperationIdAndDeletedAtIsNull(operationId)
                    .orElseThrow(() -> new IllegalStateException("change が見つからない: " + operationId));
            locked.setStripeInvoiceRef(invoiceRef);
            locked.setStatus(BillingContractChangeStatus.APPLIED);
            // AC-41: ここで明示的に flush し、uk_bcc_invoice の一意制約違反を
            // pointer 解放より前・このトランザクションの中で検出する（早期発見・巻き戻り確定）。
            changeRepository.saveAndFlush(locked);

            BillingContractEntity contract = contractRepository
                    .findByIdAndDeletedAtIsNull(locked.getContractId())
                    .orElseThrow(() -> new IllegalStateException(
                            "contract が見つからない: " + locked.getContractId()));
            contract.setPlanKey(locked.getToPlanKey());
            contract.setPriceBandVersionId(locked.getToPriceBandVersionId());
            contract.setPriceJpySnapshot(locked.getToAmountIncludingTax().intValue());
            contractRepository.save(contract);
            return null;
        });
    }

    /**
     * 失敗確定（AC-38/AC-39/AC-40）。change を {@code FAILED} にし、旧権利を維持したまま
     * operation を {@code FAILED}、pointer を削除する——すべて同一トランザクション。
     *
     * @param change    {@link #resolveByInvoice}/{@link #resolveByOperationId} が返した change
     * @param errorCode operation に刻む error_code
     */
    public void confirmFailed(BillingContractChangeEntity change, String errorCode) {
        if (!IN_FLIGHT.contains(change.getStatus())) {
            log.info("PR6b-1: 既に確定済みの change への失敗再送を無視する: changeId={}, status={}",
                    change.getId(), change.getStatus());
            return;
        }
        BillingContractChangeEntity locked = changeRepository
                .findByOperationIdAndDeletedAtIsNull(change.getOperationId())
                .orElseThrow(() -> new IllegalStateException(
                        "change が見つからない: " + change.getOperationId()));
        locked.setStatus(BillingContractChangeStatus.FAILED);
        changeRepository.save(locked);
        // 旧権利は一切触らない（契約行は据え置き）。operation の FAILED 化＋pointer 解放だけを行う。
        sagaService.failAndRelease(change.getOperationId(), errorCode);
    }
}
