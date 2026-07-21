package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.FeeBreakdown;
import com.mannschaft.app.payment.FeePolicy;
import com.mannschaft.app.payment.FeePolicyEntity;
import com.mannschaft.app.payment.FeePolicyRepository;
import com.mannschaft.app.payment.MembershipSubscriptionStatus;
import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.PaymentFeeCalculator;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.WebhookIdempotencyService;
import com.mannschaft.app.payment.WebhookProcessStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.escrow.EscrowCaptureMode;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import com.mannschaft.app.payment.escrow.LedgerAccount;
import com.mannschaft.app.payment.escrow.LedgerEntryBuilder;
import com.mannschaft.app.payment.escrow.LedgerEntryEntity;
import com.mannschaft.app.payment.escrow.LedgerEntryRepository;
import com.mannschaft.app.payment.escrow.LedgerEntryType;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * F08.9 P5 第三波: 継続課金の platform Webhook 受信サービス（{@code invoice.*} / {@code customer.subscription.deleted}・
 * 設計書 02 §4.2 / README §4.2）。
 *
 * <p>継続課金の Stripe Subscription は platform 上に作成されるため、各サイクルの invoice 系イベント・解約イベントは
 * platform Webhook（{@code POST /api/v1/webhooks/stripe}）で届く。{@link StripeWebhookService} が {@code invoice.*} /
 * {@code customer.subscription.deleted} を本サービスへ委譲する（既存 EP に種別追加のみ・新 EP は作らない）。</p>
 *
 * <h2>扱うイベント（設計書 02 §4.2）</h2>
 * <ul>
 *   <li><b>{@code invoice.created}（★核心）</b>: subscription を逆引きし、焼き付けた {@code fee_policy_key} を復元して
 *       {@code face_amount} から固定手数料を算出し、その invoice の {@code application_fee_amount} を draft 窓で上書き
 *       （subscription の率自動計算を完全上書き）。{@code billing_reason=subscription_cycle} かつ {@code draft} のみ対象。
 *       上書き失敗は握り潰さず {@link StripeWebhookRetryableException} を投げ、Stripe 再送（draft 窓 約1時間・指数
 *       バックオフ）でリカバリさせる。</li>
 *   <li><b>{@code invoice.paid}</b>: {@code escrow_transaction(MEMBERSHIP, CAPTURED)}＋{@code ledger_entries} 起票・
 *       {@code member_payments(PAID)} 生成・受益者 valid_until を1サイクル延長・current_period 更新。PAST_DUE からの
 *       再試行成功は {@code markRecovered}。</li>
 *   <li><b>{@code invoice.payment_failed}</b>: {@code markPastDue}＋WARN（督促は Stripe smart retries に委譲・§6）。</li>
 *   <li><b>{@code customer.subscription.deleted}</b>: {@code markCancelled}（期末解約の完了 / 再試行尽き）。</li>
 * </ul>
 *
 * <p><b>PENDING→ACTIVE は本サービスで行わない（活性化点の一元化）:</b> 案b では初回会費は単発 destination charge で
 * 徴収し Subscription は次サイクルから起動するため、初回 {@code invoice.paid} は発生しない。PENDING→ACTIVE は
 * 「初回 charge の CAPTURED」を唯一の起点とし {@link MembershipSubscriptionService#activateOnInitialChargeIfPending}
 * が担う（{@link com.mannschaft.app.payment.escrow.MembershipPaymentCaptureListener} 経由）。本サービスが
 * {@code invoice.paid} で PENDING を観測した場合（案 a 相当・案b では起きない想定）も、活性化の二重発火を避けるため
 * 同じ活性化メソッドへ委譲し、本サービス内で独自に markActive しない。</p>
 *
 * <h2>冪等・並行（設計書 02 §4.2）</h2>
 * <ul>
 *   <li>{@code event_id} 冪等ゲート（{@link WebhookIdempotencyService}）を全イベントで通す。確定済み（真の重複）は no-op。</li>
 *   <li>{@code membership_subscriptions} 更新は {@code PESSIMISTIC_WRITE} 行ロック（EscrowWebhookService の流儀）。</li>
 *   <li>{@code invoice.paid} の escrow/member_payments 起票は PaymentIntent ID で既存を引いて二重起票を防ぐ
 *       （FAILED 再処理・再送に強い）。</li>
 * </ul>
 *
 * <h2>帳簿（PoC 実証・README §4.2）</h2>
 * <p>destination charge では {@code transfer} は<b>額面全額</b>で起票され application fee は受取側残高から別途回収される
 * （純着金=額面−fee）。{@code ledger_entries} は P1 capture と同じ複式記帳（CAPTURE 借方＝額面、TRANSFER_OUT 貸方＝
 * 額面−fee、FEE 貸方＝fee）でこの 2 段（送金全額＋手数料別回収）を表現する（借方合計＝貸方合計）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MembershipSubscriptionWebhookService {

    /** 継続課金 platform Webhook で本サービスが受けるイベント種別（接頭辞 invoice. ＋ subscription.deleted）。 */
    static final String INVOICE_EVENT_PREFIX = "invoice.";
    static final String SUBSCRIPTION_DELETED_EVENT = "customer.subscription.deleted";

    /** 固定手数料上書きの対象とする課金理由（更新サイクルのみ・初回 subscription_create は案b で発生しない＝防御）。 */
    private static final String BILLING_REASON_SUBSCRIPTION_CYCLE = "subscription_cycle";

    /** application_fee_amount 上書きが可能な invoice 状態（draft 窓のみ・finalize 後は窓が閉じる）。 */
    private static final String INVOICE_STATUS_DRAFT = "draft";

    private final StripePaymentProvider stripePaymentProvider;
    private final WebhookIdempotencyService idempotencyService;
    private final MembershipSubscriptionRepository membershipSubscriptionRepository;
    private final MembershipSubscriptionService membershipSubscriptionService;
    private final EscrowTransactionRepository escrowTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final MemberPaymentRepository memberPaymentRepository;
    private final FeePolicyRepository feePolicyRepository;
    private final PaymentFeeCalculator paymentFeeCalculator;

    /**
     * 本サービスが対象とするイベントか（{@link StripeWebhookService} の委譲判定で用いる）。
     */
    public static boolean isSubscriptionEvent(String type) {
        return type != null && (type.startsWith(INVOICE_EVENT_PREFIX) || SUBSCRIPTION_DELETED_EVENT.equals(type));
    }

    /**
     * 継続課金 platform Webhook を処理する。署名検証 → {@code event_id} 冪等ゲート → ハンドラの順。
     *
     * <p><b>失敗の握り潰し禁止:</b> dispatch 失敗時は受信記録を {@code FAILED} へ独立コミットしたうえで例外を再送出する。
     * {@code invoice.created} の上書き失敗は {@link StripeWebhookRetryableException} で表し、platform Controller が
     * 本例外型のみ再送出して 500 を返す（Stripe 再送・draft 窓内でリカバリ）。記帳系（invoice.paid 等）の失敗も
     * 同様に再送に委ねる（冪等ゲートが FAILED を再処理可と判定）。</p>
     *
     * @param payload   生リクエストボディ
     * @param sigHeader {@code Stripe-Signature} ヘッダー
     */
    public void handleWebhook(String payload, String sigHeader) {
        StripePaymentProvider.InvoiceWebhookEventInfo event =
                stripePaymentProvider.constructInvoiceEvent(payload, sigHeader);

        boolean shouldProcess =
                idempotencyService.tryBegin(event.eventId(), event.type(), event.livemode());
        if (!shouldProcess) {
            return;
        }

        WebhookProcessStatus result;
        try {
            result = dispatch(event);
        } catch (RuntimeException e) {
            idempotencyService.markFailed(event.eventId());
            log.warn("継続課金 Webhook ハンドラ失敗。FAILED 記録のうえ再送出します: eventId={}, type={}",
                    event.eventId(), event.type(), e);
            throw e;
        }
        idempotencyService.markProcessed(event.eventId(), result);
    }

    private WebhookProcessStatus dispatch(StripePaymentProvider.InvoiceWebhookEventInfo event) {
        return switch (event.type()) {
            case "invoice.created" -> applyInvoiceCreated(event);
            case "invoice.paid" -> applyInvoicePaid(event);
            case "invoice.payment_failed" -> applyInvoicePaymentFailed(event);
            case SUBSCRIPTION_DELETED_EVENT -> applySubscriptionDeleted(event);
            default -> {
                log.info("未対応の継続課金 Webhook イベント: type={}", event.type());
                yield WebhookProcessStatus.IGNORED;
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // invoice.created — ★固定手数料上書き（本波の核心）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code invoice.created}: 焼き付けた {@code fee_policy_key} で固定手数料を算出し draft invoice の
     * {@code application_fee_amount} を上書きする（設計書 02 §4.2・PoC 実証）。
     *
     * <p>対象は {@code billing_reason=subscription_cycle} かつ {@code draft}（finalize 前の窓）。初回
     * {@code subscription_create}（案b では発生しない想定）・draft 以外・スキップ月（void で invoice 自体が来ない）は
     * no-op＋WARN。対象 subscription が無い（他テナントの無関係 invoice）も no-op＋WARN。上書きの API 失敗は
     * 握り潰さず {@link StripeWebhookRetryableException} を投げ Stripe 再送に委ねる（draft 窓は約1時間・指数バックオフで
     * 間に合う見込み）。</p>
     */
    private WebhookProcessStatus applyInvoiceCreated(StripePaymentProvider.InvoiceWebhookEventInfo event) {
        if (event.subscriptionId() == null) {
            // subscription 紐付かない invoice（請求書単発等）は継続課金の対象外。
            log.info("invoice.created だが subscription 紐付なし。no-op: invoiceId={}", event.invoiceId());
            return WebhookProcessStatus.IGNORED;
        }

        // 更新サイクルのみ上書き（subscription_create は案b で発生しない想定だが防御的に対象外）。
        if (!BILLING_REASON_SUBSCRIPTION_CYCLE.equals(event.billingReason())) {
            log.warn("invoice.created の billing_reason が subscription_cycle でないため上書きしない（防御）: "
                            + "invoiceId={}, subscriptionId={}, billingReason={}",
                    event.invoiceId(), event.subscriptionId(), event.billingReason());
            return WebhookProcessStatus.IGNORED;
        }

        // 上書きは draft 窓のみ（finalize 後は application_fee_amount を変更できない）。
        if (!INVOICE_STATUS_DRAFT.equals(event.invoiceStatus())) {
            log.warn("invoice.created だが draft でないため上書き不可（窓外）: invoiceId={}, subscriptionId={}, status={}",
                    event.invoiceId(), event.subscriptionId(), event.invoiceStatus());
            return WebhookProcessStatus.IGNORED;
        }

        MembershipSubscriptionEntity subscription = membershipSubscriptionRepository
                .findByStripeSubscriptionIdAndDeletedAtIsNull(event.subscriptionId())
                .orElse(null);
        if (subscription == null) {
            // 他テナントの無関係 invoice 等。症状を隠さず WARN を残し no-op（IGNORED）。例外を投げて再送させない
            // （対象不在は再送しても解決しないため・無限再送を避ける）。
            log.warn("invoice.created だが対象 subscription なし（無関係 invoice）。no-op: invoiceId={}, subscriptionId={}",
                    event.invoiceId(), event.subscriptionId());
            return WebhookProcessStatus.IGNORED;
        }

        // 焼き付けた fee_policy_key を復元（consume のみ・FeePolicyResolver は改変しない）。無効/不在は組み込み既定へ
        // フォールバック（症状を隠さず WARN・遡及防止の焼き付け値を最大限尊重）。
        long expectedFee = computeFixedApplicationFee(subscription);

        try {
            stripePaymentProvider.updateInvoiceApplicationFee(
                    event.invoiceId(), expectedFee, "invoice-fee-" + event.invoiceId());
        } catch (RuntimeException e) {
            // ★上書き失敗は絶対に握り潰さない: ERROR ログ（invoiceId/subscriptionId/期待手数料）＋再送誘導例外。
            // draft 窓（約1時間）内に Stripe の at-least-once 再送（指数バックオフ）でリカバリさせるのが正道
            //（握り潰すと率手数料のまま finalize→pay されて折半が崩れる損失が確定する）。
            log.error("★invoice.created 固定手数料上書き失敗（再送に委ねる）: invoiceId={}, subscriptionId={}, expectedFee={}, "
                            + "feePolicyKey={}, faceAmount={}",
                    event.invoiceId(), event.subscriptionId(), expectedFee, subscription.getFeePolicyKey(),
                    subscription.getFaceAmount(), e);
            throw new StripeWebhookRetryableException(
                    "invoice.created application_fee_amount 上書き失敗 invoiceId=" + event.invoiceId(), e);
        }

        log.info("★invoice.created 固定手数料上書き成功: invoiceId={}, subscriptionId={}, appFee={}, feePolicyKey={}, face={}",
                event.invoiceId(), event.subscriptionId(), expectedFee, subscription.getFeePolicyKey(),
                subscription.getFaceAmount());
        return WebhookProcessStatus.PROCESSED;
    }

    /**
     * 焼き付けた {@code fee_policy_key} を復元し（consume）、price-lock 焼き付けの {@code face_amount} から
     * 固定 application_fee を算出する（{@link PaymentFeeCalculator} 一元化・数式を再実装しない）。
     *
     * <p>{@link com.mannschaft.app.payment.FeePolicyResolver} は (source_kind, sub_key) からの解決専用であり、
     * 「焼き付けたキーで復元」する API は持たない。よって {@link FeePolicyRepository} で policy_key を直接引いて
     * 値オブジェクトへ変換する（Resolver/Calculator/fee_policies は consume のみ・改変しない）。無効/不在は組み込み
     * 既定（率5%＋固定0）へフォールバックし {@code NullPointerException}（症状を隠した連鎖故障）を起こさない。</p>
     */
    private long computeFixedApplicationFee(MembershipSubscriptionEntity subscription) {
        return computeFeeBreakdown(subscription).applicationFeeAmount();
    }

    /**
     * 焼き付けた {@code fee_policy_key} と {@code face_amount} から手数料内訳一式を復元する。
     *
     * <p>{@code application_fee_amount} の上書き（{@code invoice.created}）と記帳（{@code invoice.paid}）の
     * 双方が本メソッドの単一の内訳を使うことで、上書き額と記帳額が構造的に食い違わないようにする。</p>
     */
    private FeeBreakdown computeFeeBreakdown(MembershipSubscriptionEntity subscription) {
        FeePolicy policy = feePolicyRepository
                .findByPolicyKeyAndEnabledTrue(subscription.getFeePolicyKey())
                .map(FeePolicyEntity::toFeePolicy)
                .orElseGet(() -> {
                    log.warn("焼き付けた fee_policy_key が無効/不在。組み込み既定（率5%＋固定0）へフォールバック: "
                            + "feePolicyKey={}, subscriptionId={}", subscription.getFeePolicyKey(), subscription.getId());
                    return FeePolicy.defaultPolicy();
                });
        return paymentFeeCalculator.calculate((long) subscription.getFaceAmount(), policy);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // invoice.paid — escrow/ledger/member_payments 起票・valid_until 延長・状態反映
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code invoice.paid}: 各サイクルの支払い成立。escrow(MEMBERSHIP, CAPTURED)＋ledger 起票・member_payments(PAID)
     * 生成・current_period 更新・状態反映（PAST_DUE→ACTIVE は markRecovered／ACTIVE は current_period 延長のみ）。
     *
     * <p>スキップ月は invoice 自体が void され {@code invoice.paid} が発火しないため、本ハンドラは valid_until を
     * 延ばさない＝閲覧も延びない（ペイウォール無改修で整合・README §4.5）。</p>
     */
    private WebhookProcessStatus applyInvoicePaid(StripePaymentProvider.InvoiceWebhookEventInfo event) {
        if (event.subscriptionId() == null) {
            log.info("invoice.paid だが subscription 紐付なし。no-op: invoiceId={}", event.invoiceId());
            return WebhookProcessStatus.IGNORED;
        }

        // subscription を行ロックして取得（read-then-write 直列化・並行/再送の二重遷移防止）。
        MembershipSubscriptionEntity subscription = membershipSubscriptionRepository
                .findByStripeSubscriptionIdForUpdate(event.subscriptionId())
                .orElse(null);
        if (subscription == null) {
            log.warn("invoice.paid だが対象 subscription なし（無関係 invoice）。no-op: invoiceId={}, subscriptionId={}",
                    event.invoiceId(), event.subscriptionId());
            return WebhookProcessStatus.IGNORED;
        }

        LocalDate periodStart = toLocalDateOrNull(event.periodStartEpochSec());
        LocalDate periodEnd = toLocalDateOrNull(event.periodEndEpochSec());
        if (periodStart == null) {
            periodStart = LocalDate.now();
        }
        if (periodEnd == null) {
            // Stripe が期間を返さない異常時は課金周期で算出（valid_until は必ず1サイクル延長する）。
            periodEnd = addOneCycle(periodStart, subscription);
        }

        // 状態反映（PENDING→ACTIVE は本サービスで行わない＝活性化点の一元化）。
        switch (subscription.getStatus()) {
            case PENDING -> {
                // 案b では起きない想定（初回は単発 charge・初回 invoice なし）。万一観測したら活性化点（初回 charge
                // CAPTURED 連動メソッド）へ委譲し本サービス内で markActive しない（二重発火防止）。
                log.warn("invoice.paid で PENDING を観測（案b では想定外）。活性化点へ委譲（本サービスでは markActive しない）: "
                        + "subscriptionId={}, invoiceId={}", subscription.getId(), event.invoiceId());
                membershipSubscriptionService.activateOnInitialChargeIfPending(subscription.getId());
            }
            case PAST_DUE -> {
                // 再試行成功（PAST_DUE→ACTIVE 復帰・valid_until を現在から1サイクル延長）。
                subscription.markRecovered(periodStart, periodEnd);
                membershipSubscriptionRepository.save(subscription);
                log.info("invoice.paid 再試行成功 PAST_DUE→ACTIVE 復帰: subscriptionId={}, periodEnd={}",
                        subscription.getId(), periodEnd);
            }
            case ACTIVE -> {
                // 通常サイクル更新（current_period をサイクル分前進＝valid_until 1サイクル延長）。
                // toBuilder は親クラスの id を失うためミューテータで更新する（subscription 連結の null 化を防ぐ）。
                subscription.applyCurrentPeriod(periodStart, periodEnd);
                membershipSubscriptionRepository.save(subscription);
                log.info("invoice.paid 通常サイクル更新（valid_until 1サイクル延長）: subscriptionId={}, periodEnd={}",
                        subscription.getId(), periodEnd);
            }
            default -> {
                // CANCELLED/EXPIRED への支払い成立は整合性異常（症状を隠さず WARN）。記帳は行う（金は動いた）。
                log.warn("invoice.paid だが subscription が終端状態。状態遷移はせず記帳のみ行う: subscriptionId={}, status={}",
                        subscription.getId(), subscription.getStatus());
            }
        }

        // escrow(MEMBERSHIP, CAPTURED)＋ledger＋member_payments(PAID) を起票（PI ID で二重起票防止）。
        recordCapturedCycle(subscription, event, periodStart, periodEnd);
        return WebhookProcessStatus.PROCESSED;
    }

    /**
     * サイクル成立の記帳: escrow(MEMBERSHIP, CAPTURED)＋複式記帳＋member_payments(PAID) を起票する。
     *
     * <p>destination charge の帳簿（PoC 実証・README §4.2）に従い、CAPTURE 借方＝額面、TRANSFER_OUT 貸方＝額面−fee
     * （送金は額面全額だが純着金は額面−fee）、FEE 貸方＝fee の 2 段で表現する（借方合計＝貸方合計・P1 capture と同型）。
     * 既に当該 invoice の PaymentIntent で escrow 起票済みなら二重起票しない（FAILED 再処理・再送に強い）。</p>
     */
    private void recordCapturedCycle(MembershipSubscriptionEntity subscription,
                                     StripePaymentProvider.InvoiceWebhookEventInfo event,
                                     LocalDate periodStart, LocalDate periodEnd) {
        String stripeObjectId = event.paymentIntentId() != null ? event.paymentIntentId() : event.chargeId();

        // 二重起票防止（PI ID 既存チェック）。PI が無い（稀）場合は event_id 冪等ゲートに委ねる。
        if (event.paymentIntentId() != null
                && escrowTransactionRepository.findByStripePaymentIntentId(event.paymentIntentId()).isPresent()) {
            log.info("invoice.paid だが当該 PI の escrow は起票済み（冪等・再起票しない）: piId={}, subscriptionId={}",
                    event.paymentIntentId(), subscription.getId());
            return;
        }

        // 記帳は「額面」ではなく「実請求額（chargeAmount）」基準で行う（案C・手数料折半の根治）。
        // 案C の Subscription は「会費 Price（額面）＋支払側手数料 Price（payerFee）」の 2 明細で構成され、
        // invoice 合計＝chargeAmount（例 10,250）となるため、初回サイクルの PaymentIntent と同額が実際に動く。
        // ここを faceAmount のままにすると TRANSFER_OUT が 9,500 となり、受取側が毎月「額面の折半分」を
        // 余分に負担しているように記帳されて実残高と乖離する（正しくは 9,750）。
        FeeBreakdown fee = computeFeeBreakdown(subscription);
        long faceAmount = fee.faceAmount();
        long chargeAmount = fee.chargeAmount();
        long appFee = fee.applicationFeeAmount();
        long transferOut = fee.transferAmount();

        EscrowTransactionEntity escrow = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.MEMBERSHIP)
                .captureMode(EscrowCaptureMode.AUTOMATIC)
                .sourceId(subscription.getPaymentItemId())
                .sourceParticipantId(null)
                .payerScopeKind(ScopeKind.USER)
                .payerScopeId(subscription.getPayerUserId())
                .payerStripeCustomerId(subscription.getStripeCustomerId())
                .payeeKind(subscription.getScopeKind())
                .payeeConnectAccountId(subscription.getPayeeConnectAccountId())
                .organizationId(subscription.getOrganizationId())
                .faceAmount(faceAmount)
                .amount(chargeAmount)
                .currency(subscription.getCurrency())
                .applicationFeeAmount(appFee)
                .feePolicyKey(subscription.getFeePolicyKey())
                .status(EscrowStatus.CAPTURED)
                .stripePaymentIntentId(event.paymentIntentId())
                .authorizedAt(LocalDateTime.now())
                .capturedAt(LocalDateTime.now())
                .holdExpiresAt(null)
                .build();
        escrow = escrowTransactionRepository.save(escrow);

        // 複式記帳（CAPTURE 借方＝実請求額 chargeAmount、TRANSFER_OUT 貸方＝chargeAmount−fee、FEE 貸方＝fee。
        // 送金は請求額全額・fee は受取側残高から別途回収＝純着金 chargeAmount−fee の 2 段を表現・
        // README §4.2 / 01 §3.3。借方合計＝貸方合計）。
        // 例（額面 10,000・DEFAULT 5%）: 借方 10,250 ＝ 貸方 9,750（受取側）＋ 500（プラットフォーム）。
        List<LedgerEntryEntity> entries = LedgerEntryBuilder.forTransaction(escrow.getId(), escrow.getCurrency())
                .debit(LedgerEntryType.CAPTURE, LedgerAccount.ESCROW, chargeAmount, stripeObjectId)
                .credit(LedgerEntryType.TRANSFER_OUT, LedgerAccount.PAYEE, transferOut, stripeObjectId)
                .credit(LedgerEntryType.FEE, LedgerAccount.PLATFORM_FEE, appFee, stripeObjectId)
                .build();
        ledgerEntryRepository.saveAll(entries);

        // member_payments(PAID) をサイクル分起票（継続課金連結・受益者 valid_until=periodEnd 同期）。
        MemberPaymentEntity payment = MemberPaymentEntity.builder()
                .userId(subscription.getBeneficiaryUserId())
                .paymentItemId(subscription.getPaymentItemId())
                .amountPaid(java.math.BigDecimal.valueOf(faceAmount))
                .currency(subscription.getCurrency())
                .paymentMethod(PaymentMethod.STRIPE)
                .status(PaymentStatus.PAID)
                .payerUserId(subscription.getPayerUserId())
                .payerRelationship(PayerRelationship.SELF)
                .escrowTransactionId(escrow.getId())
                .membershipSubscriptionId(subscription.getId())
                .stripePaymentIntentId(event.paymentIntentId())
                .validFrom(periodStart)
                .validUntil(periodEnd)
                .paidAt(LocalDateTime.now())
                .build();
        memberPaymentRepository.save(payment);

        log.info("invoice.paid 記帳完了: subscriptionId={}, escrowId={}, memberPaymentId={}, face={}, charge={}, "
                        + "transfer={}, fee={}, validUntil={}",
                subscription.getId(), escrow.getId(), payment.getId(), faceAmount, chargeAmount, transferOut, appFee,
                periodEnd);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // invoice.payment_failed / customer.subscription.deleted
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code invoice.payment_failed}: {@code markPastDue}＋WARN（督促は Stripe smart retries に委譲・§6・dunning と
     * 二重送信しない）。スキップ月（pause void）は paid/failed とも発火しないため本ハンドラは到達しない（防御）。
     */
    private WebhookProcessStatus applyInvoicePaymentFailed(StripePaymentProvider.InvoiceWebhookEventInfo event) {
        if (event.subscriptionId() == null) {
            log.info("invoice.payment_failed だが subscription 紐付なし。no-op: invoiceId={}", event.invoiceId());
            return WebhookProcessStatus.IGNORED;
        }
        MembershipSubscriptionEntity subscription = membershipSubscriptionRepository
                .findByStripeSubscriptionIdForUpdate(event.subscriptionId())
                .orElse(null);
        if (subscription == null) {
            log.warn("invoice.payment_failed だが対象 subscription なし。no-op: invoiceId={}, subscriptionId={}",
                    event.invoiceId(), event.subscriptionId());
            return WebhookProcessStatus.IGNORED;
        }
        if (subscription.getStatus() != MembershipSubscriptionStatus.ACTIVE) {
            // ACTIVE 以外（PENDING/PAST_DUE/終端）からの失敗通知は遷移しない（PAST_DUE は既に督促状態・冪等）。
            log.warn("invoice.payment_failed だが subscription が ACTIVE でないため遷移しない: subscriptionId={}, status={}",
                    subscription.getId(), subscription.getStatus());
            return WebhookProcessStatus.PROCESSED;
        }
        subscription.markPastDue();
        membershipSubscriptionRepository.save(subscription);
        log.warn("invoice.payment_failed → PAST_DUE（督促は Stripe smart retries に委譲）: subscriptionId={}, invoiceId={}",
                subscription.getId(), event.invoiceId());
        return WebhookProcessStatus.PROCESSED;
    }

    /**
     * {@code customer.subscription.deleted}: {@code markCancelled}＋{@code cancelled_at}（期末解約の完了 / 再試行尽き）。
     * 既に終端（CANCELLED/EXPIRED）なら冪等 no-op。
     */
    private WebhookProcessStatus applySubscriptionDeleted(StripePaymentProvider.InvoiceWebhookEventInfo event) {
        if (event.subscriptionId() == null) {
            log.info("customer.subscription.deleted だが subscription ID なし。no-op");
            return WebhookProcessStatus.IGNORED;
        }
        MembershipSubscriptionEntity subscription = membershipSubscriptionRepository
                .findByStripeSubscriptionIdForUpdate(event.subscriptionId())
                .orElse(null);
        if (subscription == null) {
            log.warn("customer.subscription.deleted だが対象 subscription なし。no-op: subscriptionId={}",
                    event.subscriptionId());
            return WebhookProcessStatus.IGNORED;
        }
        if (subscription.getStatus() == MembershipSubscriptionStatus.CANCELLED
                || subscription.getStatus() == MembershipSubscriptionStatus.EXPIRED) {
            log.info("customer.subscription.deleted だが既に終端状態（冪等 no-op）: subscriptionId={}, status={}",
                    subscription.getId(), subscription.getStatus());
            return WebhookProcessStatus.PROCESSED;
        }
        subscription.markCancelled();
        membershipSubscriptionRepository.save(subscription);
        log.info("customer.subscription.deleted → CANCELLED（期末解約の完了/再試行尽き）: subscriptionId={}",
                subscription.getId());
        return WebhookProcessStatus.PROCESSED;
    }

    private LocalDate toLocalDateOrNull(Long epochSec) {
        return epochSec == null ? null
                : Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private LocalDate addOneCycle(LocalDate from, MembershipSubscriptionEntity subscription) {
        return (subscription.getBillingInterval() == com.mannschaft.app.payment.BillingInterval.YEARLY)
                ? from.plusYears(1) : from.plusMonths(1);
    }
}
