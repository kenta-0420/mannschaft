package com.mannschaft.app.billing;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.billing.api.BillingInvoiceJpaRepository;
import com.mannschaft.app.billing.invoice.StripeBillingPayloadParser;
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
 *
 * <h2>公開範囲（第13隊・AC-142 是正）</h2>
 * <p>本サービスの唯一の呼び出し元は同一パッケージの {@link BillingSubscriptionWebhookService}
 * である。{@code BillingContractChangeEntity} を引数・戻り値に持つメソッドを {@code public} の
 * ままにすると、番人 {@code ServiceApiEntityBoundaryArchTest}（D-1 API boundary）が
 * 「他ドメインから呼ばれうる Service API が Entity を公開している」として検出する
 * （凍結ストアは chip-away 運用で {@code freeze.refreeze=false}。新規違反を凍結へ
 * 追記することは禁止されている）。実際に他ドメインから呼ばれることはないため、
 * package-private（同パッケージ限定）へ絞ることで根治する。</p>
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
    /** PR6b-1 第9隊 AC-80: pending_update_applied を確定してよいかの再確認（invoice.paid 済みか）に使う。 */
    private final BillingInvoiceJpaRepository invoiceRepository;
    /** PR6b-1 第9隊 AC-79: pending_update_target_snapshot から price ref を取り出す。 */
    private final StripeBillingPayloadParser payloadParser;
    /** PR6b-1 第13隊 AC-137/AC-138: change の確定・失敗を監査する。 */
    private final AuditLogService auditLogService;

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
    Optional<BillingContractChangeEntity> resolveByInvoice(String invoiceRef, String subscriptionRef) {
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
    Optional<BillingContractChangeEntity> resolveByOperationId(UUID operationId) {
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
    void confirmPaid(BillingContractChangeEntity change, String invoiceRef) {
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
        // AC-137: 確定（invoice.paid）が commit された後に監査する（IN_FLIGHT だったときだけ
        // ここへ到達するため、二度目以降の冪等 no-op 再送は監査しない）。
        audit(AuditEventType.BILLING_PLAN_CHANGE_APPLIED, change, null);
    }

    /**
     * PR6b-1 第9隊 AC-74/78/83: {@code invoice.payment_action_required} を受けて
     * {@code REQUIRES_ACTION} へ進める（二重照合済み。呼び出し元は owner/customer 一致を確認済み）。
     *
     * <p><b>単調維持（AC-82/83）</b>: {@link #IN_FLIGHT} でない（すでに {@code APPLIED}/{@code FAILED}
     * 等の terminal）なら no-op。遅延到着した古い event が確定済みの状態を巻き戻さない。
     * すでに {@code REQUIRES_ACTION} なら再送として冪等 no-op（AC-86）。</p>
     */
    void confirmRequiresAction(BillingContractChangeEntity change) {
        if (!IN_FLIGHT.contains(change.getStatus())) {
            log.info("PR6b-1: 既に確定済みの change への action_required 再送を無視する: changeId={}, status={}",
                    change.getId(), change.getStatus());
            return;
        }
        if (change.getStatus() == BillingContractChangeStatus.REQUIRES_ACTION) {
            return; // 冪等（AC-86）。
        }
        BillingContractChangeEntity locked = changeRepository
                .findByOperationIdAndDeletedAtIsNull(change.getOperationId())
                .orElseThrow(() -> new IllegalStateException(
                        "change が見つからない: " + change.getOperationId()));
        if (locked.getStatus() != BillingContractChangeStatus.PENDING_PAYMENT) {
            return; // すでに他イベントで進んでいれば触らない（単調維持）。
        }
        locked.setStatus(BillingContractChangeStatus.REQUIRES_ACTION);
        changeRepository.save(locked);
    }

    /**
     * PR6b-1 第9隊 AC-79/80: pending_update（3DS）経路の {@code invoice.paid} は支払いの成立<b>だけ</b>を
     * 記録し、{@code APPLIED} への遷移は行わない。
     *
     * <p><b>なぜ即時 APPLIED にしないのか</b>: {@link #confirmPaid} は change の {@code toPlanKey} 等
     * <b>保存済みの値</b>をそのまま契約へ書く。同期成功（{@code pending_update_expires_at} が
     * NULL）なら Stripe が items を即時に切り替えているので問題ないが、3DS 経由（同カラムが
     * 非NULL）は「支払いが成功した」時点でまだ Stripe 側の items 切替が反映されているとは限らない
     * （invoice.paid と customer.subscription.pending_update_applied は別イベントで到着順序も
     * 保証されない）。確定は {@code pending_update_applied} が現在の items を保存済み target と
     * 照合できてから行う（E2'・{@link #confirmAppliedIfMatchingItems}）。</p>
     */
    void acknowledgePendingPayment(BillingContractChangeEntity change, String invoiceRef) {
        if (!IN_FLIGHT.contains(change.getStatus())) {
            return;
        }
        if (invoiceRef == null || invoiceRef.equals(change.getStripeInvoiceRef())) {
            return;
        }
        BillingContractChangeEntity locked = changeRepository
                .findByOperationIdAndDeletedAtIsNull(change.getOperationId())
                .orElseThrow(() -> new IllegalStateException(
                        "change が見つからない: " + change.getOperationId()));
        locked.setStripeInvoiceRef(invoiceRef);
        changeRepository.saveAndFlush(locked);
    }

    /**
     * PR6b-1 第9隊 AC-75/79/80: {@code customer.subscription.pending_update_applied} を確定する。
     *
     * <h2>二段の再確認</h2>
     * <ol>
     *   <li><b>AC-80</b>: {@code invoice.paid} 済み（{@code stripe_invoice_ref} で引ける
     *       {@code billing_invoices} 行の {@code paid_at} が非NULL）でなければ確定しない。</li>
     *   <li><b>AC-79（E2'）</b>: change 行へ<b>保存した</b> {@code pending_update_target_snapshot} から
     *       取り出した price ref と、event が運ぶ<b>現在の items</b>（{@code currentItemPriceRef}）が
     *       一致しなければ確定しない（適用後の Subscription からは live な {@code pending_update}
     *       を取得できないため、保存値と現在値の突合せで判定する）。</li>
     * </ol>
     *
     * <p>両方満たせば {@link #confirmPaid} へ委譲する（AC-37 と同じ確定ロジックを再利用し、
     * change/operation/権利/pointer の一括反映を保証する）。満たさなければ何もしない（次の
     * event を待つ）。</p>
     *
     * @return 確定した（もしくは既に確定済みで no-op とした）なら {@code true}。まだ確定条件が
     *         整っていない（呼び出し元は {@code PROCESSED} として扱ってよい・再送で拾い直す必要は無い）
     *         なら {@code false}
     */
    boolean confirmAppliedIfMatchingItems(BillingContractChangeEntity change, String currentItemPriceRef) {
        if (!IN_FLIGHT.contains(change.getStatus())) {
            return true; // 既に確定済み（AC-86 の冪等）。
        }
        String invoiceRef = change.getStripeInvoiceRef();
        boolean paidConfirmed = invoiceRef != null
                && invoiceRepository.findByPspInvoiceRef(invoiceRef)
                        .map(inv -> inv.getPaidAt() != null)
                        .orElse(false);
        if (!paidConfirmed) {
            log.info("PR6b-1: invoice.paid 未確認のため pending_update_applied を確定しない（AC-80）: changeId={}",
                    change.getId());
            return false;
        }
        String targetPriceRef = payloadParser.targetPriceRefFromSnapshot(change.getPendingUpdateTargetSnapshot());
        if (targetPriceRef == null || !targetPriceRef.equals(currentItemPriceRef)) {
            log.info("PR6b-1: 保存した target と現在 items が一致しないため確定しない（E2'・AC-79）: "
                    + "changeId={}, target={}, current={}", change.getId(), targetPriceRef, currentItemPriceRef);
            return false;
        }
        confirmPaid(change, invoiceRef);
        return true;
    }

    /**
     * 失敗確定（AC-38/AC-39/AC-40）。change を {@code FAILED} にし、旧権利を維持したまま
     * operation を {@code FAILED}、pointer を削除する——すべて同一トランザクション。
     *
     * @param change    {@link #resolveByInvoice}/{@link #resolveByOperationId} が返した change
     * @param errorCode operation に刻む error_code
     */
    void confirmFailed(BillingContractChangeEntity change, String errorCode) {
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
        // AC-138: 確定失敗（decline/voided/expired）も監査する（成功だけを監査しない・PR6a AC-66 と同方針）。
        audit(AuditEventType.BILLING_PLAN_CHANGE_FAILED, locked, errorCode);
    }

    /**
     * 監査を1件記録する（AC-137/AC-138）。
     *
     * <p>metadata に載せるのは <b>scopeKind / scopeId / contractId / changeId / fromPlanKey /
     * toPlanKey / errorCode</b> だけである。Stripe の raw payload・clientSecret・カード情報・住所は
     * 一切載せない（AC-139・{@code BillingContractCancelApplicationService#audit} と同型）。
     * webhook 起点で操作者ログインが無いため、userId には change を起票した actor
     * （{@link BillingContractChangeEntity#getCreatedBy()}）を用いる。</p>
     */
    private void audit(AuditEventType eventType, BillingContractChangeEntity change, String errorCode) {
        BillingContractEntity contract = contractRepository
                .findByIdAndDeletedAtIsNull(change.getContractId()).orElse(null);
        EntitlementScopeKind scopeKind = contract == null ? null : contract.getScopeKind();
        Long scopeId = contract == null ? null : contract.getScopeId();
        StringBuilder metadata = new StringBuilder()
                .append("{\"scopeKind\":\"").append(scopeKind == null ? "UNKNOWN" : scopeKind.name())
                .append("\",\"scopeId\":").append(scopeId)
                .append(",\"contractId\":\"").append(change.getContractId()).append('"')
                .append(",\"changeId\":\"").append(change.getId()).append('"')
                .append(",\"fromPlanKey\":\"").append(change.getFromPlanKey()).append('"')
                .append(",\"toPlanKey\":\"").append(change.getToPlanKey()).append('"');
        if (errorCode != null) {
            metadata.append(",\"errorCode\":\"").append(errorCode).append('"');
        }
        metadata.append('}');
        auditLogService.record(eventType.name(), change.getCreatedBy(), null,
                scopeKind == EntitlementScopeKind.TEAM ? scopeId : null,
                scopeKind == EntitlementScopeKind.ORG ? scopeId : null,
                null, null, null, metadata.toString());
    }
}
