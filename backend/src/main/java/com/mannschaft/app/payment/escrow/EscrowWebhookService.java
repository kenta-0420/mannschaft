package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.payment.WebhookIdempotencyService;
import com.mannschaft.app.payment.WebhookProcessStatus;
import com.mannschaft.app.payment.escrow.event.EscrowCapturedEvent;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
 *   <li>{@code payment_intent.amount_capturable_updated} → <b>真の与信確定</b>（札主が confirm した＝カード上の
 *       ホールドが立った）。{@link EscrowStatus#PENDING_CONFIRMATION} → {@link EscrowStatus#AUTHORIZED} へ昇格し、
 *       {@code authorized_at} と hold 失効基準 {@code hold_expires_at} を確定する（第一陣 status 意味論の根治）。</li>
 *   <li>{@code payment_intent.canceled} / {@code payment_intent.payment_failed} → {@link EscrowStatus#CANCELLED}
 *       （confirm 前の取消・カード拒否等）。</li>
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

    /**
     * AUTHORIZED（真の与信確定）の hold 失効基準（最大7日・設計書 02 §5.1）。
     *
     * <p>第一陣 status 意味論の根治: hold は札主の confirm（amount_capturable_updated）で初めて立つため、
     * {@code hold_expires_at} は authorize 時ではなく本 webhook の AUTHORIZED 昇格時に刻む。</p>
     */
    static final long AUTHORIZED_HOLD_DAYS = 7L;

    private final StripePaymentProvider stripePaymentProvider;
    private final WebhookIdempotencyService idempotencyService;
    private final EscrowTransactionRepository escrowTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final RefundRepository refundRepository;
    private final ApplicationEventPublisher eventPublisher;

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

    /**
     * {@code charge.refunded}（返金確定）を処理する（設計書 02 §6.1）。
     *
     * <p>{@code charge.refunded} は謝礼/会費（escrow）と既存会員費（{@code MemberPaymentEntity}）の両方で発生しうる。
     * 本メソッドは<b>対象 escrow が存在する場合のみ</b>処理し、存在しなければ {@code false} を返して呼び出し元
     * （{@code StripeWebhookService}）が会費側へフォールバックできるようにする。escrow が存在する場合のみ
     * {@code event_id} 冪等ゲートを消費する（会費側 charge.refunded のゲートと競合させない）。</p>
     *
     * <p><b>二重処理防止（根治・02 §6.1）:</b> escrow 行を {@code PESSIMISTIC_WRITE} でロックして
     * read-then-write を直列化する（refund API の同期処理／再送 webhook との競合防止・P2-c-1 と同様）。</p>
     *
     * @param payload   生リクエストボディ
     * @param sigHeader {@code Stripe-Signature} ヘッダー
     * @return escrow として処理した場合 {@code true}、対象 escrow が無く会費側へ委譲すべき場合 {@code false}
     */
    public boolean handleChargeRefunded(String payload, String sigHeader) {
        StripePaymentProvider.EscrowWebhookEventInfo event =
                stripePaymentProvider.constructEscrowEvent(payload, sigHeader);

        // 対象 escrow が無ければ会費側へフォールバック（冪等ゲートは消費しない）。
        if (event.paymentIntentId() == null
                || escrowTransactionRepository.findByStripePaymentIntentId(event.paymentIntentId()).isEmpty()) {
            log.info("charge.refunded だが対象 escrow なし。会費側へフォールバック: piId={}", event.paymentIntentId());
            return false;
        }

        boolean shouldProcess = idempotencyService.tryBegin(event.eventId(), event.type(), event.livemode());
        if (!shouldProcess) {
            return true;
        }

        WebhookProcessStatus result;
        try {
            result = applyChargeRefunded(event);
        } catch (RuntimeException e) {
            idempotencyService.markFailed(event.eventId());
            log.warn("charge.refunded ハンドラ失敗。FAILED 記録のうえ再送出します: eventId={}", event.eventId(), e);
            throw e;
        }
        idempotencyService.markProcessed(event.eventId(), result);
        return true;
    }

    /**
     * {@code charge.refunded} 本処理: 対象 {@code refunds} を {@code SUCCEEDED} 確定し、累計が額面に達したか否かで
     * escrow を {@link EscrowStatus#REFUNDED}/{@link EscrowStatus#PARTIALLY_REFUNDED} へ確定する。
     *
     * <p>既に {@code SUCCEEDED} 済みの refund は二重確定しない（冪等 no-op）。escrow 行ロックで二重処理を防ぐ。</p>
     */
    private WebhookProcessStatus applyChargeRefunded(StripePaymentProvider.EscrowWebhookEventInfo event) {
        EscrowTransactionEntity escrow = findEscrowForUpdateOrNull(event.paymentIntentId());
        if (escrow == null) {
            return WebhookProcessStatus.IGNORED;
        }

        RefundEntity refund = event.refundId() == null ? null
                : refundRepository.findByStripeRefundId(event.refundId()).orElse(null);
        if (refund == null) {
            // refund 行が未登録（refund API 経由でない外部返金等）。症状を隠さず情報ログを残し IGNORED。
            log.info("charge.refunded だが対応する refunds 行が未登録: refundId={}, piId={}",
                    event.refundId(), event.paymentIntentId());
            return WebhookProcessStatus.IGNORED;
        }
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            log.info("charge.refunded だが既に SUCCEEDED 済み（冪等 no-op）: refundId={}", event.refundId());
            return WebhookProcessStatus.PROCESSED;
        }

        refund.setStatus(RefundStatus.SUCCEEDED);
        refundRepository.save(refund);

        // 支払者負担モデル（02 §6.1）: refunds.amount は「支払者へ戻した額＝transferAmount ベース」。
        // 返金の上限は受取側が受け取った正味＝transferAmount（amount − application_fee）であり、額面ではない。
        // 累計（SUCCEEDED 済み）が transferAmount に達したか否かで escrow 状態を確定する（refund API と整合）。
        long transferAmount = escrow.getAmount() - escrow.getApplicationFeeAmount();
        long totalRefunded = refundRepository.findByEscrowTransactionId(escrow.getId()).stream()
                .filter(r -> r.getStatus() == RefundStatus.SUCCEEDED)
                .mapToLong(RefundEntity::getAmount)
                .sum();
        EscrowStatus newStatus = totalRefunded >= transferAmount ? EscrowStatus.REFUNDED : EscrowStatus.PARTIALLY_REFUNDED;
        if (escrow.getStatus() != newStatus) {
            escrow.setStatus(newStatus);
            escrowTransactionRepository.save(escrow);
        }
        log.info("charge.refunded 確定: escrowId={}, refundId={}, status={}, 累計={}/{}（transferベース）",
                escrow.getId(), event.refundId(), newStatus, totalRefunded, transferAmount);
        return WebhookProcessStatus.PROCESSED;
    }

    private WebhookProcessStatus dispatch(StripePaymentProvider.EscrowWebhookEventInfo event) {
        return switch (event.type()) {
            case "payment_intent.amount_capturable_updated" -> applyAmountCapturable(event.paymentIntentId());
            case "payment_intent.canceled", "payment_intent.payment_failed" -> applyCanceled(event.paymentIntentId());
            case "payment_intent.succeeded" -> applySucceeded(event.paymentIntentId());
            default -> {
                log.info("未対応の Escrow Webhook イベント: type={}", event.type());
                yield WebhookProcessStatus.IGNORED;
            }
        };
    }

    /**
     * {@code payment_intent.amount_capturable_updated}（札主が confirm し<b>真の与信が立った</b>）→ AUTHORIZED へ昇格する。
     *
     * <p>第一陣 status 意味論の根治: manual-capture PI は札主の confirm で初めて amount_capturable が立つ。
     * 本イベントが「confirm 完了＝真の与信確定」のシグナルであり、{@link EscrowStatus#PENDING_CONFIRMATION}
     * （PI 作成済・confirm 待ち）を {@link EscrowStatus#AUTHORIZED}（capture 可能）へ昇格させる。HELD から
     * onboarding 完了で confirm に至ったケースもここで AUTHORIZED 化する。昇格時に {@code authorized_at} と
     * hold 失効基準 {@code hold_expires_at} を確定する（authorize 時には立てない・与信が立つまで未確定のため）。
     * 既に AUTHORIZED（再送）も冪等に AUTHORIZED のまま。CAPTURED 等の後段状態は触らない。</p>
     */
    private WebhookProcessStatus applyAmountCapturable(String paymentIntentId) {
        EscrowTransactionEntity escrow = findEscrowOrNull(paymentIntentId);
        if (escrow == null) {
            return WebhookProcessStatus.IGNORED;
        }
        // PENDING_CONFIRMATION（confirm 待ち）/HELD（onboarding 完了）/AUTHORIZED（再送・冪等）から AUTHORIZED を確定。
        if (escrow.getStatus() == EscrowStatus.PENDING_CONFIRMATION
                || escrow.getStatus() == EscrowStatus.HELD
                || escrow.getStatus() == EscrowStatus.AUTHORIZED) {
            LocalDateTime now = LocalDateTime.now();
            escrow.setStatus(EscrowStatus.AUTHORIZED);
            if (escrow.getAuthorizedAt() == null) {
                escrow.setAuthorizedAt(now);
            }
            if (escrow.getHoldExpiresAt() == null) {
                // hold は confirm（真の与信）で立つため、ここで失効基準（最大7日）を刻む（02 §5.1）。
                escrow.setHoldExpiresAt(now.plusDays(AUTHORIZED_HOLD_DAYS));
            }
            escrowTransactionRepository.save(escrow);
            log.info("真の与信確定（amount_capturable_updated・札主 confirm 完了）: escrowId={}, piId={}",
                    escrow.getId(), paymentIntentId);
        } else {
            log.info("amount_capturable_updated だが対象 escrow は後段状態のため無視: escrowId={}, status={}",
                    escrow.getId(), escrow.getStatus());
        }
        return WebhookProcessStatus.PROCESSED;
    }

    /**
     * {@code payment_intent.canceled} / {@code payment_intent.payment_failed} → CANCELLED へ遷移する。
     *
     * <p>capture 前（{@link EscrowStatus#PENDING_CONFIRMATION}＝confirm 前のキャンセル/カード拒否、
     * {@link EscrowStatus#AUTHORIZED}＝与信成立後の札下げ/失効、{@link EscrowStatus#HELD}）からのみ取消する。
     * 課金は起きていないため refunds には記録しない（与信取消・02 §6.1）。CAPTURED 等の後段状態は触らない。</p>
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
        if (escrow.getStatus() == EscrowStatus.PENDING_CONFIRMATION
                || escrow.getStatus() == EscrowStatus.AUTHORIZED
                || escrow.getStatus() == EscrowStatus.HELD) {
            escrow.setStatus(EscrowStatus.CANCELLED);
            escrow.setCancelledAt(LocalDateTime.now());
            escrowTransactionRepository.save(escrow);
            log.info("与信取消（payment_intent.canceled/payment_failed）: escrowId={}, piId={}",
                    escrow.getId(), paymentIntentId);
        } else {
            log.info("payment_intent.canceled/payment_failed だが対象 escrow は後段状態のため無視: escrowId={}, status={}",
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
     *
     * <p><b>完了時即時払い（第三陣-b 7日超 fallback・02 §5.1）:</b> {@link EscrowStatus#DEFERRED} から最終認証で
     * 起票した即時払いは {@link EscrowCaptureMode#AUTOMATIC} の PaymentIntent で、会費 charge() と同じく
     * {@link EscrowStatus#AUTHORIZED}（PI 作成済・succeeded 待ち）に置かれる。AUTOMATIC PI は札主が confirm すると
     * （manual の amount_capturable 段を経ず）直接 {@code payment_intent.succeeded} を発火するため、本ハンドラの
     * 既存 AUTHORIZED 経路がそのまま CAPTURED 化＋記帳する（追加分岐は不要）。</p>
     *
     * <p><b>会費の PAID 反映（F08.9 P1 Wave4・02 §1.1 / §4.2）:</b> CAPTURED へ遷移したとき
     * {@link EscrowCapturedEvent} を発火する。本イベントは {@code member_payments} を知らない escrow ドメインと
     * 会費ドメイン（{@link MembershipPaymentCaptureListener}）の境界を保つための結節点であり、リスナが
     * {@code sourceKind=MEMBERSHIP} のみ拾って {@code member_payments} を PENDING→PAID にする。発火は CAPTURED へ
     * <b>新規に遷移した場合のみ</b>（既に CAPTURED の冪等 no-op パスでは発火しない＝二重 PAID 反映を避ける。
     * リスナ側も冪等だが、発火自体を遷移時に限定して無駄な配送を抑える）。{@code AFTER_COMMIT} 配送は
     * リスナ側の {@code TransactionalEventListener(AFTER_COMMIT)} が担保する（CAPTURED 確定が durable に
     * なってから PAID 反映が走る）。</p>
     */
    private WebhookProcessStatus applySucceeded(String paymentIntentId) {
        // 二重記帳防止（根治・02 §5.3）: capture（同期フック）と本 webhook の read-then-write を行ロックで
        // 直列化する。ロック取得後に status を再判定（CAPTURED なら no-op）するため、ロック付きで取得する。
        EscrowTransactionEntity escrow = findEscrowForUpdateOrNull(paymentIntentId);
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

            // 会費の PAID 反映トリガ（F08.9 P1 Wave4・02 §1.1）: CAPTURED へ新規遷移した場合のみイベント発火。
            // escrow は member_payments を知らないため、payment(F08.9)側の MembershipPaymentCaptureListener が
            // sourceKind=MEMBERSHIP のみ拾って PENDING→PAID にする（ドメイン境界を維持・逆依存を作らない）。
            // 配送は AFTER_COMMIT（リスナ側）— CAPTURED 確定が durable になってから PAID 反映が走る。
            eventPublisher.publishEvent(new EscrowCapturedEvent(escrow.getId(), escrow.getSourceKind()));
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

    /**
     * capture × webhook の二重記帳防止のため、escrow 行を {@code PESSIMISTIC_WRITE} ロックして取得する
     * （read-then-write 直列化・02 §5.3）。{@code payment_intent.succeeded}（capture 確定）でのみ用いる。
     */
    private EscrowTransactionEntity findEscrowForUpdateOrNull(String paymentIntentId) {
        if (paymentIntentId == null) {
            log.warn("Escrow Webhook に paymentIntentId が含まれていません");
            return null;
        }
        return escrowTransactionRepository.findByStripePaymentIntentIdForUpdate(paymentIntentId).orElseGet(() -> {
            log.info("paymentIntentId に対応する escrow が未登録。無視します: piId={}", paymentIntentId);
            return null;
        });
    }
}
