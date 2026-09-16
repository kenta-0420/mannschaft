package com.mannschaft.app.billing;

import com.mannschaft.app.billing.invoice.BillingInvoiceOwner;
import com.mannschaft.app.billing.invoice.BillingInvoiceProjectionService;
import com.mannschaft.app.billing.invoice.BillingWebhookEventGate;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.EventEnvelope;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.InvoiceView;
import com.mannschaft.app.billing.invoice.StripeBillingPayloadParser;
import com.mannschaft.app.payment.WebhookIdempotencyService;
import com.mannschaft.app.payment.WebhookProcessStatus;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import com.mannschaft.app.payment.stripe.StripePaymentProvider.BillingSubscriptionWebhookEventInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * F20.1 実決済（D-1〜D-4・2026-07-10 御裁可）: 自社受取サブスクの platform Webhook 受信サービス。
 *
 * <p>{@link com.mannschaft.app.payment.service.StripeWebhookService} から委譲され、billing 所有イベントのみを
 * 処理する。所有判定は「{@code checkout.session.*}＝{@code metadata.billingContractId} の有無」「{@code invoice.*} /
 * {@code customer.subscription.deleted}＝{@code psp_subscription_ref} 逆引きヒットの有無」で行い、billing の
 * subscription でなければ何もせず {@code false} を返す（F08.9 会費 webhook へフォールバック・D-2）。</p>
 *
 * <p><b>冪等の二層</b>: (1) {@link WebhookIdempotencyService}（event_id ゲート・at-least-once 再送耐性）＋
 * (2) 各状態遷移メソッドの status 済みチェック（二重発行ゼロ・AC-34）。dispatch 失敗は握り潰さず FAILED 記録＋再送出。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingSubscriptionWebhookService {

    private static final String CHECKOUT_COMPLETED = "checkout.session.completed";
    private static final String CHECKOUT_EXPIRED = "checkout.session.expired";
    private static final String INVOICE_EVENT_PREFIX = "invoice.";
    private static final String INVOICE_FINALIZED = "invoice.finalized";
    private static final String INVOICE_VOIDED = "invoice.voided";
    private static final String INVOICE_PAID = "invoice.paid";
    private static final String INVOICE_PAYMENT_FAILED = "invoice.payment_failed";
    /** PR6b-1 第9隊 AC-74: 3DS 等の追加認証要求。upgrade の REQUIRES_ACTION 遷移の入口。 */
    private static final String INVOICE_PAYMENT_ACTION_REQUIRED = "invoice.payment_action_required";
    private static final String SUBSCRIPTION_DELETED = "customer.subscription.deleted";
    /** PR6a AC-83: 停止窓の回収の入口（プラン変更の APPLIED 判定は PR6b の担当）。 */
    private static final String SUBSCRIPTION_UPDATED =
            BillingContractOperationRecoveryService.RECOVERY_ENTRY_EVENT_TYPE;
    /** PR6b-1 AC-40/76: 3DS の追加認証が失効した合図。upgrade の失敗確定の入口。 */
    public static final String SUBSCRIPTION_PENDING_UPDATE_EXPIRED =
            "customer.subscription.pending_update_expired";
    /** PR6b-1 第9隊 AC-75: pending_update（3DS）が適用された合図。upgrade の適用確定の入口。 */
    public static final String SUBSCRIPTION_PENDING_UPDATE_APPLIED =
            "customer.subscription.pending_update_applied";

    private final StripePaymentProvider stripePaymentProvider;
    private final WebhookIdempotencyService idempotencyService;
    private final BillingContractService billingContractService;
    private final BillingContractRepository billingContractRepository;
    /** F20.1 PR5: invoice 投影（3 表への不変投影と fail-closed 検証）。 */
    private final BillingInvoiceProjectionService invoiceProjectionService;
    /** F20.1 PR5: 冪等・所有記録・失敗回数・再送判断の共通ゲート。 */
    private final BillingWebhookEventGate webhookEventGate;
    /** F20.1 PR5: 署名検証済み payload から event 封筒を読む（created の単調更新判定に使う）。 */
    private final StripeBillingPayloadParser payloadParser;
    private final BillingPayerHandoverService payerHandoverService;
    /** PR6a AC-83: {@code customer.subscription.updated} を停止窓の回収の入口として使う。 */
    private final BillingContractOperationRecoveryService operationRecoveryService;
    /** PR6b-1 第7隊: upgrade 確定の原子性（AC-37〜45）。 */
    private final BillingPlanChangeConfirmationService planChangeConfirmationService;
    private final Clock clock;

    /**
     * {@code checkout.session.completed} を処理する（billing 所有＝metadata に billingContractId あり）。
     *
     * @return billing が処理したら {@code true}（呼び出し側はフォールバックしない）。billing 非所有なら {@code false}。
     */
    public boolean handleCheckoutCompletedIfBilling(String payload, String sigHeader) {
        BillingSubscriptionWebhookEventInfo event = stripePaymentProvider.constructBillingSubscriptionEvent(payload, sigHeader);
        if (!CHECKOUT_COMPLETED.equals(event.type()) || event.billingContractId() == null) {
            return false;
        }
        return runGated(event, () -> {
            UUID contractId = UUID.fromString(event.billingContractId());

            // 柱③-B: 引継の新契約（PENDING_HANDOVER）は通常の有償契約とは別経路で確定させる。
            //
            // ここで activatePaidContract を通してはならない。同メソッドは契約を ACTIVE 化したうえで
            // active_contract_pointers を張るが、引継では旧契約が旧期末まで pointer を保持し続けており、
            // スロット単位 UNIQUE（uk_acp_slot）と衝突して webhook 処理ごと落ちる（設計書 §3.1 P0-4）。
            // 引継の新契約は PENDING_HANDOVER のまま据え置き、pointer の付け替えは「旧期末到達」を条件に
            // 切替TXが行う。本 webhook が担うのは (a)引継確定条件の成立、すなわち要求を SWITCHING へ進め、
            // 同時に旧サブスクへ cancel_at_period_end=true を予約することである（設計書 §3.6 表(a)・AC-6/AC-31）。
            java.util.Optional<UUID> handoverRequestId =
                    billingContractRepository.findById(contractId)
                            .map(BillingContractEntity::getHandoverRequestId)
                            .filter(java.util.Objects::nonNull);
            if (handoverRequestId.isPresent()) {
                payerHandoverService.onHandoverCheckoutCompleted(
                        handoverRequestId.get(), event.subscriptionId());
                return WebhookProcessStatus.PROCESSED;
            }

            billingContractService.activatePaidContract(
                    contractId, event.customerId(), event.subscriptionId(),
                    toLdt(event.currentPeriodEndEpochSec()));
            return WebhookProcessStatus.PROCESSED;
        });
    }

    /**
     * {@code checkout.session.expired} を処理する（billing 所有＝metadata に billingContractId あり）。
     */
    public boolean handleCheckoutExpiredIfBilling(String payload, String sigHeader) {
        BillingSubscriptionWebhookEventInfo event = stripePaymentProvider.constructBillingSubscriptionEvent(payload, sigHeader);
        if (!CHECKOUT_EXPIRED.equals(event.type()) || event.billingContractId() == null) {
            return false;
        }
        return runGated(event, () -> {
            UUID contractId = UUID.fromString(event.billingContractId());

            // 柱③-B: 引継の新契約（PENDING_HANDOVER）は通常契約とは別経路で放棄する（設計書 §3.6・検分1巡目 P1-3）。
            //
            // abandonPendingContract は (1) PENDING 以外を no-op とするため PENDING_HANDOVER には効かず、
            // (2) スロット単位で pointer を物理 DELETE するため、仮に効かせると旧契約の entitlement を
            // 巻き添えで剥がす（引継では旧契約が旧期末まで同じスロットの pointer を保持し続けている）。
            // よって引継側では承諾を REQUESTED へ巻き戻し、旧契約に触れないまま再承諾可能な状態へ戻す。
            java.util.Optional<UUID> handoverRequestId =
                    billingContractRepository.findById(contractId)
                            .map(BillingContractEntity::getHandoverRequestId)
                            .filter(java.util.Objects::nonNull);
            if (handoverRequestId.isPresent()) {
                // イベント元の契約 ID を渡す。過去の承諾試行の expired が遅れて届いても、
                // その後に成立した別 ADMIN の承諾を巻き戻さないための照合キーである。
                payerHandoverService.onHandoverCheckoutExpired(handoverRequestId.get(), contractId);
                return WebhookProcessStatus.PROCESSED;
            }

            billingContractService.abandonPendingContract(contractId);
            return WebhookProcessStatus.PROCESSED;
        });
    }

    /**
     * {@code invoice.*} / {@code customer.subscription.deleted} を処理する（billing 所有＝psp_subscription_ref 逆引きヒット）。
     *
     * @return billing の subscription なら処理して {@code true}。無関係（F08.9 会費 等）なら {@code false}。
     */
    public boolean handleSubscriptionEventIfBilling(String payload, String sigHeader) {
        BillingSubscriptionWebhookEventInfo event = stripePaymentProvider.constructBillingSubscriptionEvent(payload, sigHeader);
        if (event.type() != null && event.type().startsWith(INVOICE_EVENT_PREFIX)) {
            return handleInvoiceEventIfBilling(payload, event);
        }

        String subscriptionId = event.subscriptionId();
        if (subscriptionId == null
                || billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull(subscriptionId).isEmpty()) {
            // billing の subscription ではない → F08.9 会費側へフォールバック（相互 no-op・AC-38）。
            return false;
        }
        if (SUBSCRIPTION_UPDATED.equals(event.type())) {
            return handleSubscriptionUpdatedForRecovery(event, subscriptionId);
        }
        if (SUBSCRIPTION_PENDING_UPDATE_EXPIRED.equals(event.type())) {
            return handlePendingUpdateExpired(event);
        }
        if (SUBSCRIPTION_PENDING_UPDATE_APPLIED.equals(event.type())) {
            return handlePendingUpdateApplied(event, payload);
        }

        return runGated(event, () -> switch (event.type()) {
            case SUBSCRIPTION_DELETED -> {
                billingContractService.expireSubscriptionContract(subscriptionId, toLdt(event.currentPeriodEndEpochSec()));
                yield WebhookProcessStatus.PROCESSED;
            }
            default -> {
                log.info("F20.1 決済: 未対応の billing subscription イベント: type={}", event.type());
                yield WebhookProcessStatus.IGNORED;
            }
        });
    }

    /**
     * {@code invoice.*} を処理する（F20.1 PR5）。
     *
     * <p><b>所有判定（AC-25）</b>: {@code psp_subscription_ref} の DB ヒット<b>単独では所有と断定しない</b>。
     * {@code invoice.customer} が scope 所有の {@code billing_customers} に一致することを併せて確かめる。
     * subscription は自分のものなのに customer が別人という検体は、所有はしているが処理できない
     * <b>一時失敗</b>として扱い（確定させず attempt_count を積む）、200 で握り潰さない（AC-13）。</p>
     *
     * <p><b>一体性（AC-18/20）</b>: invoice 投影と契約遷移は同一トランザクション・同一イベントで成否する。
     * 片方だけコミットされることはない。</p>
     */
    /**
     * {@code customer.subscription.pending_update_expired} を処理する（AC-40）。
     *
     * <p>invoice を経由しない失敗確定の入口。event の {@code metadata.billingOperationId}
     * （{@link BillingSubscriptionWebhookEventInfo#billingOperationId()}）から直接 change を
     * 解決する。upgrade の change でなければ所有を主張しない（{@code false}）。</p>
     */
    private boolean handlePendingUpdateExpired(BillingSubscriptionWebhookEventInfo event) {
        UUID operationId = event.billingOperationId() == null || event.billingOperationId().isBlank()
                ? null : UUID.fromString(event.billingOperationId());
        Optional<BillingContractChangeEntity> change =
                planChangeConfirmationService.resolveByOperationId(operationId);
        if (change.isEmpty()) {
            return false;
        }
        return runGated(event, () -> {
            planChangeConfirmationService.confirmFailed(change.get(), "PENDING_UPDATE_EXPIRED");
            return WebhookProcessStatus.PROCESSED;
        });
    }

    /**
     * {@code customer.subscription.pending_update_applied} を処理する（PR6b-1 第9隊 AC-75/79/80）。
     *
     * <p>invoice を経由しない適用確定の入口。event の {@code metadata.billingOperationId} から
     * 直接 change を解決する（{@link #handlePendingUpdateExpired} と同型）。upgrade の change でなければ
     * 所有を主張しない（{@code false}）。確定条件（invoice.paid 済み・E2' の items 照合）は
     * {@link BillingPlanChangeConfirmationService#confirmAppliedIfMatchingItems} に委ねる。</p>
     */
    private boolean handlePendingUpdateApplied(BillingSubscriptionWebhookEventInfo event, String payload) {
        UUID operationId = event.billingOperationId() == null || event.billingOperationId().isBlank()
                ? null : UUID.fromString(event.billingOperationId());
        Optional<BillingContractChangeEntity> change =
                planChangeConfirmationService.resolveByOperationId(operationId);
        if (change.isEmpty()) {
            return false;
        }
        String currentItemPriceRef = payloadParser.parseSubscription(payload)
                .map(com.mannschaft.app.billing.invoice.StripeBillingObjectView.SubscriptionView::currentItemPriceRef)
                .orElse(null);
        return runGated(event, () -> {
            boolean confirmed = planChangeConfirmationService
                    .confirmAppliedIfMatchingItems(change.get(), currentItemPriceRef);
            if (!confirmed) {
                // 【修繕・P1-2】この event は消費してよい。確定できなかったのは
                // invoice.paid がまだ届いていない（applied が先着した）ためであり、
                // 後続の invoice.paid が現在 items を再照合して確定する
                // （BillingPlanChangeConfirmationService#confirmPaidForPendingUpdate）。
                // Stripe は applied を再発行しないため、この受け皿が無いと change は
                // 永久に REQUIRES_ACTION のまま固まる。
                log.info("PR6b-1: pending_update_applied 時点では確定条件が未成立。後続の invoice.paid に委ねる: "
                        + "eventId={}", event.eventId());
            }
            return WebhookProcessStatus.PROCESSED;
        });
    }

    private boolean handleInvoiceEventIfBilling(String payload, BillingSubscriptionWebhookEventInfo event) {
        Optional<InvoiceView> invoiceView = invoiceProjectionService.readInvoice(payload);
        Optional<BillingInvoiceOwner> owner = invoiceView.flatMap(invoiceProjectionService::resolveOwner);
        Optional<BillingContractEntity> bySubscription = event.subscriptionId() == null
                ? Optional.empty()
                : billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull(event.subscriptionId());

        if (owner.isEmpty() && bySubscription.isEmpty()) {
            // どちらの経路でも自分のものだと言えない → 未消費のまま F08.9 会費側へフォールバック（AC-7）。
            return false;
        }

        long eventCreated = payloadParser.parseEnvelope(payload)
                .map(EventEnvelope::createdEpochSec)
                .filter(sec -> sec > 0L)
                .orElseGet(() -> clock.instant().getEpochSecond());
        EventEnvelope envelope = new EventEnvelope(
                event.eventId(), event.type(), event.livemode(), eventCreated);
        String invoiceRef = invoiceView.map(InvoiceView::id).orElse(null);

        if (owner.isEmpty()) {
            if (invoiceView.isEmpty()) {
                // invoice の本文が読めない（＝customer を照合しようがない）。subscription の一致だけを
                // 根拠に従来どおり契約遷移だけ行う。投影は行わない（投影の材料が無い）。
                BillingContractEntity contract = bySubscription.get();
                return webhookEventGate.runWithStatus(envelope, payload, invoiceRef,
                        contract.getId(), contract.getBillingCustomerId(),
                        () -> applyContractTransition(event, invoiceRef));
            }
            // subscription は billing のものだが customer が scope 所有 Customer と一致しない。
            // 所有と断定できないので投影せず、確定もさせない（再送で customer 行が整えば成功しうる）。
            return webhookEventGate.runWithStatus(envelope, payload, invoiceRef, null, null,
                    () -> {
                        throw new IllegalStateException(
                                "invoice.customer が scope 所有 Customer と一致しないため投影できません: invoice="
                                        + invoiceRef + ", customer=" + invoiceView.get().customerRef());
                    });
        }

        BillingInvoiceOwner resolved = owner.get();
        return webhookEventGate.runWithStatus(envelope, payload, invoiceRef,
                resolved.contractId(), resolved.billingCustomerId(),
                () -> {
                    invoiceProjectionService.project(
                            invoiceView.get(), resolved, event.type(), envelope.createdEpochSec());
                    return applyContractTransition(event, invoiceRef);
                });
    }

    /**
     * invoice イベントに対応する契約側の遷移を適用する（投影と同一トランザクション）。
     *
     * <p><b>E8（PR6b-1・AC-44/45）</b>: この invoice が upgrade の差額請求（{@code billing_contract_changes}
     * に対応行がある）なら、契約本体の {@code markContractPastDue}/{@code extendContractPeriod} へは
     * <b>流用しない</b>。旧プランの通常請求は滞っていないのに契約が {@code PAST_DUE} に落ちる、
     * 差額の支払いで契約期間が延長される、という実在の誤遷移を遮断する。確定は
     * {@link BillingPlanChangeConfirmationService} が change 側だけで完結させる。</p>
     */
    private WebhookProcessStatus applyContractTransition(
            BillingSubscriptionWebhookEventInfo event, String invoiceRef) {
        String subscriptionId = event.subscriptionId();

        Optional<BillingContractChangeEntity> planChange =
                planChangeConfirmationService.resolveByInvoice(invoiceRef, subscriptionId);
        if (planChange.isPresent()) {
            return switch (event.type()) {
                case INVOICE_PAID -> {
                    // PR6b-1 第9隊 AC-79/80（E2'）: pending_update（3DS）経路は invoice.paid だけで
                    // APPLIED にしない。適用確定は customer.subscription.pending_update_applied の
                    // 現在 items 照合に委ねる（confirmAppliedIfMatchingItems）。同期成功（pending_update
                    // を経由しない upgrade）は従来どおり invoice.paid の一点で確定する（E6'・AC-37）。
                    // 【修繕・P1-2】applied が paid より先に着いた場合、applied 側では
                    // 「invoice.paid 済み」を確認できず確定できない。Stripe は applied を
                    // 再発行しないため、paid 側で現在 items を再照合して確定しなければ
                    // change は REQUIRES_ACTION のまま固まり pointer が操作を遮断し続ける。
                    if (planChange.get().getPendingUpdateExpiresAt() != null) {
                        planChangeConfirmationService.confirmPaidForPendingUpdate(
                                planChange.get(), invoiceRef, subscriptionId);
                    } else {
                        planChangeConfirmationService.confirmPaid(planChange.get(), invoiceRef);
                    }
                    yield WebhookProcessStatus.PROCESSED;
                }
                case INVOICE_PAYMENT_ACTION_REQUIRED -> {
                    // AC-74/78: ここに来る時点で owner（customer/subscription の二重照合）は
                    // handleInvoiceEventIfBilling が既に確認済み。confirmRequiresAction 側の
                    // IN_FLIGHT 判定が単調維持（AC-82/83）と冪等（AC-86）を担う。
                    planChangeConfirmationService.confirmRequiresAction(planChange.get());
                    yield WebhookProcessStatus.PROCESSED;
                }
                case INVOICE_PAYMENT_FAILED -> {
                    planChangeConfirmationService.confirmFailed(planChange.get(), "INVOICE_PAYMENT_FAILED");
                    yield WebhookProcessStatus.PROCESSED;
                }
                case INVOICE_VOIDED -> {
                    planChangeConfirmationService.confirmFailed(planChange.get(), "INVOICE_VOIDED");
                    yield WebhookProcessStatus.PROCESSED;
                }
                case INVOICE_FINALIZED -> WebhookProcessStatus.PROCESSED;
                default -> {
                    log.info("F20.1 決済: 契約遷移を伴わない billing upgrade invoice イベント: type={}", event.type());
                    yield WebhookProcessStatus.IGNORED;
                }
            };
        }

        return switch (event.type()) {
            case INVOICE_PAID -> {
                if (subscriptionId != null) {
                    billingContractService.extendContractPeriod(
                            subscriptionId, toLdt(event.currentPeriodEndEpochSec()));
                }
                yield WebhookProcessStatus.PROCESSED;
            }
            case INVOICE_PAYMENT_FAILED -> {
                if (subscriptionId != null) {
                    billingContractService.markContractPastDue(subscriptionId);
                }
                yield WebhookProcessStatus.PROCESSED;
            }
            case INVOICE_FINALIZED, INVOICE_VOIDED -> WebhookProcessStatus.PROCESSED;
            default -> {
                log.info("F20.1 決済: 契約遷移を伴わない billing invoice イベント: type={}", event.type());
                yield WebhookProcessStatus.IGNORED;
            }
        };
    }

    /**
     * event_id 冪等ゲートを通してハンドラを実行する（再送耐性・FAILED 記録・再送出）。所有済みイベントの
     * 二重受信（確定済み）は処理せず {@code true} を返す（membership へフォールバックさせない）。
     */
    /**
     * {@code customer.subscription.updated} を<b>停止窓の回収の入口としてのみ</b>扱う（PR6a AC-83）。
     *
     * <p>Stripe が「反映した」と言ってきているため stale しきい値は適用せず、その契約に非終端
     * operation が残っていればその場で回収する。プラン変更（items 差し替え・{@code pending_update}）の
     * {@code APPLIED} 判定は PR6b の担当であり、本 PR では行わない。</p>
     *
     * <p><b>回収するものが無ければ所有を主張しない（{@code false} を返す）</b>。PR6b が扱うべき
     * プラン変更の updated をここで {@code PROCESSED} に確定させると、冪等ゲートが「確定済み」と
     * 判定して PR6b が<b>永久に拾えなくなる</b>（PR5 が保留リストで守っていたのと同じ性質）。
     * {@code false} を返せば dispatcher が従来どおり {@code RECEIVED} のまま受信記録を残す。</p>
     *
     * <p>回収を冪等ゲートより前に走らせているのはこのためである。回収自体は status CAS の
     * 更新件数で勝者を決めるため、再送で二度走っても二重には効かない（AC-82）。</p>
     *
     * @param event          署名検証済みイベント
     * @param subscriptionId billing 所有と確認済みの Stripe Subscription ID
     * @return 実際に回収したなら {@code true}（billing が所有を主張する）
     */
    private boolean handleSubscriptionUpdatedForRecovery(
            BillingSubscriptionWebhookEventInfo event, String subscriptionId) {
        if (!operationRecoveryService.recoverBySubscriptionRef(subscriptionId)) {
            return false;
        }
        return runGated(event, () -> WebhookProcessStatus.PROCESSED);
    }

    private boolean runGated(BillingSubscriptionWebhookEventInfo event, java.util.function.Supplier<WebhookProcessStatus> handler) {
        boolean shouldProcess = idempotencyService.tryBegin(event.eventId(), event.type(), event.livemode());
        if (!shouldProcess) {
            return true; // 真の重複（確定済み）。billing 所有なのでフォールバックはしない。
        }
        WebhookProcessStatus result;
        try {
            result = handler.get();
        } catch (RuntimeException e) {
            idempotencyService.markFailed(event.eventId());
            log.warn("F20.1 決済 Webhook ハンドラ失敗。FAILED 記録のうえ再送出します: eventId={}, type={}",
                    event.eventId(), event.type(), e);
            throw e;
        }
        idempotencyService.markProcessed(event.eventId(), result);
        return true;
    }

    private LocalDateTime toLdt(Long epochSec) {
        return epochSec == null ? null : LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), clock.getZone());
    }
}
