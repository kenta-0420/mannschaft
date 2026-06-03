package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.WebhookIdempotencyService;
import com.mannschaft.app.payment.WebhookProcessStatus;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F22.1 統一決済 P2-b: 与信系 platform Webhook の受信サービス（設計書 02 §4.2）。
 *
 * <p>Destination Charge の PaymentIntent は platform アカウント上に作成されるため、
 * その状態変化は platform Webhook（{@link StripePaymentProvider#constructEscrowEvent}）で受ける。
 * {@code event_id} 冪等ゲート（{@link WebhookIdempotencyService}）を通してから対象 escrow を
 * {@code stripe_payment_intent_id} で特定し状態を確定する。</p>
 *
 * <p>本波で扱うイベント:</p>
 * <ul>
 *   <li>{@code payment_intent.amount_capturable_updated} → 与信確定（{@link EscrowStatus#AUTHORIZED} を確認/確定）。</li>
 *   <li>{@code payment_intent.canceled} → {@link EscrowStatus#CANCELLED}。</li>
 *   <li>{@code payment_intent.succeeded} → capture 確定（{@link EscrowStatus#CAPTURED}・{@code captured_at}・
 *       複式記帳 CAPTURE/TRANSFER_OUT/FEE。P2-c 第一波で実装）。</li>
 * </ul>
 *
 * <p>{@code payment_intent.succeeded} は capture 反映の<b>確定/安全網</b>である。通常の謝礼払出は
 * MarketFinalize フックが同期 capture するが、ネットワーク遅延・自動 capture バッチ・即時モード（会費）等で
 * webhook 先着もありうる。既に {@link EscrowStatus#CAPTURED} なら ledger 二重記帳を避け no-op（冪等）にする。
 * 返金（reverse_transfer）は<b>次波（P2-c-2）</b>であり本サービスでは扱わない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EscrowWebhookService {

    private final StripePaymentProvider stripePaymentProvider;
    private final WebhookIdempotencyService idempotencyService;
    private final EscrowTransactionRepository escrowTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * 与信系 platform Webhook を処理する。署名検証 → {@code event_id} 冪等ゲート → ハンドラの順。
     *
     * @param payload   生リクエストボディ
     * @param sigHeader {@code Stripe-Signature} ヘッダー
     */
    public void handleWebhook(String payload, String sigHeader) {
        StripePaymentProvider.EscrowWebhookEventInfo event =
                stripePaymentProvider.constructEscrowEvent(payload, sigHeader);

        boolean shouldProcess =
                idempotencyService.tryBegin(event.eventId(), event.type(), event.livemode());
        if (!shouldProcess) {
            return;
        }

        WebhookProcessStatus result;
        try {
            result = dispatch(event);
        } catch (RuntimeException e) {
            // 握り潰さない: FAILED を独立コミットで残し、例外を再送出して Stripe 再送に委ねる。
            idempotencyService.markFailed(event.eventId());
            log.warn("Escrow Webhook ハンドラ失敗。FAILED 記録のうえ再送出します: eventId={}, type={}",
                    event.eventId(), event.type(), e);
            throw e;
        }
        idempotencyService.markProcessed(event.eventId(), result);
    }

    private WebhookProcessStatus dispatch(StripePaymentProvider.EscrowWebhookEventInfo event) {
        return switch (event.type()) {
            case "payment_intent.amount_capturable_updated" -> applyAmountCapturable(event.paymentIntentId());
            case "payment_intent.canceled" -> applyCanceled(event.paymentIntentId());
            case "payment_intent.succeeded" -> applySucceeded(event.paymentIntentId());
            default -> {
                log.info("未対応の Escrow Webhook イベント: type={}", event.type());
                yield WebhookProcessStatus.IGNORED;
            }
        };
    }

    /**
     * {@code payment_intent.amount_capturable_updated}（与信額確定）→ AUTHORIZED を確定する。
     */
    private WebhookProcessStatus applyAmountCapturable(String paymentIntentId) {
        EscrowTransactionEntity escrow = findEscrowOrNull(paymentIntentId);
        if (escrow == null) {
            return WebhookProcessStatus.IGNORED;
        }
        // 与信確定の鏡像: HELD から確定したケース等で AUTHORIZED を確定する（CAPTURED 等の後段状態は触らない）。
        if (escrow.getStatus() == EscrowStatus.AUTHORIZED || escrow.getStatus() == EscrowStatus.HELD) {
            escrow.setStatus(EscrowStatus.AUTHORIZED);
            if (escrow.getAuthorizedAt() == null) {
                escrow.setAuthorizedAt(LocalDateTime.now());
            }
            escrowTransactionRepository.save(escrow);
            log.info("与信確定（amount_capturable_updated）: escrowId={}, piId={}", escrow.getId(), paymentIntentId);
        } else {
            log.info("amount_capturable_updated だが対象 escrow は後段状態のため無視: escrowId={}, status={}",
                    escrow.getId(), escrow.getStatus());
        }
        return WebhookProcessStatus.PROCESSED;
    }

    /**
     * {@code payment_intent.canceled} → CANCELLED へ遷移する。
     */
    private WebhookProcessStatus applyCanceled(String paymentIntentId) {
        EscrowTransactionEntity escrow = findEscrowOrNull(paymentIntentId);
        if (escrow == null) {
            return WebhookProcessStatus.IGNORED;
        }
        if (escrow.getStatus() == EscrowStatus.CANCELLED) {
            // 既に取消済み（冪等）。
            return WebhookProcessStatus.PROCESSED;
        }
        if (escrow.getStatus() == EscrowStatus.AUTHORIZED || escrow.getStatus() == EscrowStatus.HELD) {
            escrow.setStatus(EscrowStatus.CANCELLED);
            escrow.setCancelledAt(LocalDateTime.now());
            escrowTransactionRepository.save(escrow);
            log.info("与信取消（payment_intent.canceled）: escrowId={}, piId={}", escrow.getId(), paymentIntentId);
        } else {
            log.info("payment_intent.canceled だが対象 escrow は後段状態のため無視: escrowId={}, status={}",
                    escrow.getId(), escrow.getStatus());
        }
        return WebhookProcessStatus.PROCESSED;
    }

    /**
     * {@code payment_intent.succeeded}（capture 確定）→ CAPTURED へ確定し複式記帳を追記する（設計書 02 §5.3）。
     *
     * <p>既に {@link EscrowStatus#CAPTURED}（MarketFinalize フックの同期 capture が先着済み等）なら ledger
     * 二重記帳を避け no-op（冪等）。AUTHORIZED/HELD（webhook 先着・即時モード等）なら CAPTURED 化し、
     * {@code captured_at} と CAPTURE/TRANSFER_OUT/FEE を記帳する（借方合計＝貸方合計・01 §3.3）。</p>
     */
    private WebhookProcessStatus applySucceeded(String paymentIntentId) {
        EscrowTransactionEntity escrow = findEscrowOrNull(paymentIntentId);
        if (escrow == null) {
            return WebhookProcessStatus.IGNORED;
        }
        if (escrow.getStatus() == EscrowStatus.CAPTURED) {
            // 既に capture 済み（同期フック先着）。ledger 二重記帳を避け no-op（冪等）。
            log.info("payment_intent.succeeded だが既に CAPTURED 済み（冪等 no-op）: escrowId={}, piId={}",
                    escrow.getId(), paymentIntentId);
            return WebhookProcessStatus.PROCESSED;
        }
        if (escrow.getStatus() == EscrowStatus.AUTHORIZED || escrow.getStatus() == EscrowStatus.HELD) {
            escrow.setStatus(EscrowStatus.CAPTURED);
            escrow.setCapturedAt(LocalDateTime.now());
            escrowTransactionRepository.save(escrow);

            long captureAmount = escrow.getAmount();
            long feeAmount = escrow.getApplicationFeeAmount();
            long transferOut = captureAmount - feeAmount;
            List<LedgerEntryEntity> entries = LedgerEntryBuilder.forTransaction(escrow.getId(), escrow.getCurrency())
                    .debit(LedgerEntryType.CAPTURE, LedgerAccount.ESCROW, captureAmount, paymentIntentId)
                    .credit(LedgerEntryType.TRANSFER_OUT, LedgerAccount.PAYEE, transferOut, paymentIntentId)
                    .credit(LedgerEntryType.FEE, LedgerAccount.PLATFORM_FEE, feeAmount, paymentIntentId)
                    .build();
            ledgerEntryRepository.saveAll(entries);
            log.info("payment_intent.succeeded → capture 確定 CAPTURED: escrowId={}, piId={}, capture={}, transfer={}, fee={}",
                    escrow.getId(), paymentIntentId, captureAmount, transferOut, feeAmount);
        } else {
            log.info("payment_intent.succeeded だが対象 escrow は capture 不能状態のため無視: escrowId={}, status={}",
                    escrow.getId(), escrow.getStatus());
        }
        return WebhookProcessStatus.PROCESSED;
    }

    private EscrowTransactionEntity findEscrowOrNull(String paymentIntentId) {
        if (paymentIntentId == null) {
            log.warn("Escrow Webhook に paymentIntentId が含まれていません");
            return null;
        }
        return escrowTransactionRepository.findByStripePaymentIntentId(paymentIntentId).orElseGet(() -> {
            log.info("paymentIntentId に対応する escrow が未登録。無視します: piId={}", paymentIntentId);
            return null;
        });
    }
}
