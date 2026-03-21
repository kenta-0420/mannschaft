package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentMapper;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.dto.BulkPaymentRequest;
import com.mannschaft.app.payment.dto.BulkPaymentResponse;
import com.mannschaft.app.payment.dto.CheckoutResponse;
import com.mannschaft.app.payment.dto.CreateManualPaymentRequest;
import com.mannschaft.app.payment.dto.MemberPaymentResponse;
import com.mannschaft.app.payment.dto.ReconcileResponse;
import com.mannschaft.app.payment.dto.RemindResponse;
import com.mannschaft.app.payment.dto.UpdatePaymentRequest;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 支払い記録サービス。手動記録・Stripe 決済・返金・CSV エクスポート等を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberPaymentService {

    private final MemberPaymentRepository memberPaymentRepository;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final PaymentItemService paymentItemService;
    private final StripePaymentProvider stripePaymentProvider;
    private final PaymentMapper paymentMapper;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    /**
     * 支払い項目ごとの支払い記録をページング取得する。
     */
    public Page<MemberPaymentResponse> listPayments(Long paymentItemId, String statusFilter, Pageable pageable) {
        Page<MemberPaymentEntity> page;
        if (statusFilter != null) {
            PaymentStatus status = PaymentStatus.valueOf(statusFilter);
            page = memberPaymentRepository.findByPaymentItemIdAndStatus(paymentItemId, status, pageable);
        } else {
            page = memberPaymentRepository.findByPaymentItemId(paymentItemId, pageable);
        }
        return page.map(paymentMapper::toMemberPaymentResponse);
    }

    /**
     * 手動支払い記録を作成する。
     */
    @Transactional
    public MemberPaymentResponse createManualPayment(Long paymentItemId, Long recordedBy,
                                                      CreateManualPaymentRequest request) {
        PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(paymentItemId);

        // DONATION 以外は重複チェック
        if (paymentItem.getType() != PaymentItemType.DONATION) {
            if (memberPaymentRepository.existsValidPaidPayment(request.getUserId(), paymentItemId)) {
                throw new BusinessException(PaymentErrorCode.ALREADY_PAID);
            }
        }

        LocalDate validFrom = request.getValidFrom() != null
                ? request.getValidFrom()
                : request.getPaidAt().toLocalDate();
        LocalDate validUntil = request.getValidUntil() != null
                ? request.getValidUntil()
                : calculateValidUntil(paymentItem.getType(), validFrom);

        MemberPaymentEntity entity = MemberPaymentEntity.builder()
                .userId(request.getUserId())
                .paymentItemId(paymentItemId)
                .amountPaid(request.getAmountPaid())
                .currency(paymentItem.getCurrency())
                .paymentMethod(PaymentMethod.MANUAL)
                .status(PaymentStatus.PAID)
                .validFrom(validFrom)
                .validUntil(validUntil)
                .paidAt(request.getPaidAt())
                .recordedBy(recordedBy)
                .note(request.getNote())
                .build();

        MemberPaymentEntity saved = memberPaymentRepository.save(entity);
        log.info("手動支払い記録: id={}, userId={}, paymentItemId={}", saved.getId(), request.getUserId(), paymentItemId);
        return paymentMapper.toMemberPaymentResponse(saved);
    }

    /**
     * 支払い記録を修正する。
     */
    @Transactional
    public MemberPaymentResponse updatePayment(Long paymentItemId, Long paymentId, UpdatePaymentRequest request) {
        MemberPaymentEntity entity = memberPaymentRepository.findByIdAndPaymentItemId(paymentId, paymentItemId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        entity.updateManualPayment(request.getAmountPaid(), request.getValidFrom(),
                request.getValidUntil(), request.getNote());
        MemberPaymentEntity saved = memberPaymentRepository.save(entity);
        log.info("支払い記録修正: id={}", paymentId);
        return paymentMapper.toMemberPaymentResponse(saved);
    }

    /**
     * 支払い記録を取り消す（CANCELLED）。
     */
    @Transactional
    public void cancelPayment(Long paymentItemId, Long paymentId) {
        MemberPaymentEntity entity = memberPaymentRepository.findByIdAndPaymentItemId(paymentId, paymentItemId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        if (entity.getStatus() == PaymentStatus.REFUNDED || entity.getStatus() == PaymentStatus.CANCELLED) {
            throw new BusinessException(PaymentErrorCode.ALREADY_REFUNDED);
        }

        entity.markAsCancelled();
        memberPaymentRepository.save(entity);
        log.info("支払い記録取り消し: id={}", paymentId);
    }

    /**
     * 一括手動支払い記録を作成する。
     */
    @Transactional
    public BulkPaymentResponse createBulkPayments(Long paymentItemId, Long recordedBy,
                                                   BulkPaymentRequest request) {
        PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(paymentItemId);

        int createdCount = 0;
        List<BulkPaymentResponse.SkippedEntry> skipped = new ArrayList<>();

        for (CreateManualPaymentRequest payment : request.getPayments()) {
            try {
                // DONATION 以外は重複チェック
                if (paymentItem.getType() != PaymentItemType.DONATION
                        && memberPaymentRepository.existsValidPaidPayment(payment.getUserId(), paymentItemId)) {
                    skipped.add(new BulkPaymentResponse.SkippedEntry(payment.getUserId(), "ALREADY_PAID"));
                    continue;
                }

                LocalDate validFrom = payment.getValidFrom() != null
                        ? payment.getValidFrom()
                        : payment.getPaidAt().toLocalDate();
                LocalDate validUntil = payment.getValidUntil() != null
                        ? payment.getValidUntil()
                        : calculateValidUntil(paymentItem.getType(), validFrom);

                MemberPaymentEntity entity = MemberPaymentEntity.builder()
                        .userId(payment.getUserId())
                        .paymentItemId(paymentItemId)
                        .amountPaid(payment.getAmountPaid())
                        .currency(paymentItem.getCurrency())
                        .paymentMethod(PaymentMethod.MANUAL)
                        .status(PaymentStatus.PAID)
                        .validFrom(validFrom)
                        .validUntil(validUntil)
                        .paidAt(payment.getPaidAt())
                        .recordedBy(recordedBy)
                        .note(payment.getNote())
                        .build();

                memberPaymentRepository.save(entity);
                createdCount++;
            } catch (Exception e) {
                skipped.add(new BulkPaymentResponse.SkippedEntry(payment.getUserId(), e.getMessage()));
            }
        }

        log.info("一括支払い記録: paymentItemId={}, created={}, skipped={}", paymentItemId, createdCount, skipped.size());
        return new BulkPaymentResponse(createdCount, skipped.size(), skipped);
    }

    /**
     * 全額返金を実行する。
     */
    @Transactional
    public MemberPaymentResponse refundPayment(Long paymentItemId, Long paymentId, Long refundedBy) {
        MemberPaymentEntity entity = memberPaymentRepository.findByIdAndPaymentItemId(paymentId, paymentItemId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        if (entity.getPaymentMethod() == PaymentMethod.MANUAL) {
            throw new BusinessException(PaymentErrorCode.MANUAL_PAYMENT_NOT_REFUNDABLE);
        }
        if (entity.getStatus() == PaymentStatus.REFUNDED || entity.getStatus() == PaymentStatus.CANCELLED) {
            throw new BusinessException(PaymentErrorCode.ALREADY_REFUNDED);
        }
        if (entity.getStatus() == PaymentStatus.PENDING) {
            throw new BusinessException(PaymentErrorCode.PENDING_PAYMENT_NOT_REFUNDABLE);
        }

        // Stripe 先、DB 後
        String refundId = stripePaymentProvider.createRefund(
                entity.getStripePaymentIntentId(), paymentId, refundedBy);

        entity.markAsRefunded(refundId);
        MemberPaymentEntity saved = memberPaymentRepository.save(entity);
        log.info("返金実行: id={}, refundId={}", paymentId, refundId);
        return paymentMapper.toMemberPaymentResponse(saved);
    }

    /**
     * Stripe Checkout セッションを作成する。
     */
    @Transactional
    public CheckoutResponse createCheckout(Long paymentItemId, Long userId) {
        PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(paymentItemId);

        if (paymentItem.getStripePriceId() == null) {
            throw new BusinessException(PaymentErrorCode.STRIPE_PRICE_NOT_SET);
        }

        // DONATION 以外は重複チェック
        if (paymentItem.getType() != PaymentItemType.DONATION) {
            if (memberPaymentRepository.existsValidPaidPayment(userId, paymentItemId)) {
                throw new BusinessException(PaymentErrorCode.ALREADY_PAID);
            }
        }

        // Stripe Customer の取得または作成
        StripeCustomerEntity stripeCustomer = stripeCustomerRepository.findByUserId(userId)
                .orElseGet(() -> {
                    String customerId = stripePaymentProvider.createCustomer("user@example.com", userId);
                    return stripeCustomerRepository.save(StripeCustomerEntity.builder()
                            .userId(userId)
                            .stripeCustomerId(customerId)
                            .build());
                });

        // PENDING レコードを作成
        MemberPaymentEntity payment = MemberPaymentEntity.builder()
                .userId(userId)
                .paymentItemId(paymentItemId)
                .amountPaid(paymentItem.getAmount())
                .currency(paymentItem.getCurrency())
                .paymentMethod(PaymentMethod.STRIPE)
                .status(PaymentStatus.PENDING)
                .build();
        payment = memberPaymentRepository.save(payment);

        // Checkout Session を作成
        String successUrl = frontendUrl + "/payment/complete?session_id={CHECKOUT_SESSION_ID}";
        String cancelUrl = frontendUrl + "/payment/cancelled";

        StripePaymentProvider.CheckoutSessionInfo sessionInfo =
                stripePaymentProvider.createCheckoutSession(
                        paymentItem.getStripePriceId(),
                        stripeCustomer.getStripeCustomerId(),
                        payment.getId(),
                        successUrl,
                        cancelUrl
                );

        payment.setStripeCheckoutSessionId(sessionInfo.sessionId());
        memberPaymentRepository.save(payment);

        log.info("Checkout セッション作成: paymentId={}, sessionId={}", payment.getId(), sessionInfo.sessionId());
        return new CheckoutResponse(sessionInfo.checkoutUrl(), sessionInfo.sessionId(), sessionInfo.expiresAt());
    }

    /**
     * 未払いリマインドを送信する。
     */
    @Transactional
    public RemindResponse sendRemind(Long paymentItemId) {
        PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(paymentItemId);

        if (paymentItem.getType() == PaymentItemType.DONATION) {
            throw new BusinessException(PaymentErrorCode.DONATION_REMIND_NOT_ALLOWED);
        }

        // TODO: 未払いメンバーの取得と通知送信（通知機能実装後）
        int notifiedCount = 0;
        log.info("リマインド送信: paymentItemId={}, notifiedCount={}", paymentItemId, notifiedCount);
        return new RemindResponse(notifiedCount, paymentItem.getName());
    }

    /**
     * 支払い状況を CSV バイト列で取得する。
     */
    public byte[] exportPaymentsCsv(Long paymentItemId) {
        PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(paymentItemId);
        List<MemberPaymentEntity> payments = memberPaymentRepository.findByPaymentItemId(paymentItemId);

        StringBuilder sb = new StringBuilder();
        // BOM 付き UTF-8
        sb.append('\uFEFF');
        sb.append("メンバーID,メンバー名,ステータス,支払い金額,通貨,支払い方法,支払い日時,有効期間開始,有効期間終了,備考\n");

        for (MemberPaymentEntity payment : payments) {
            sb.append(payment.getUserId()).append(',');
            sb.append(','); // メンバー名は別テーブルのため TODO
            sb.append(payment.getStatus().name()).append(',');
            sb.append(payment.getAmountPaid()).append(',');
            sb.append(payment.getCurrency()).append(',');
            sb.append(payment.getPaymentMethod().name()).append(',');
            sb.append(payment.getPaidAt() != null ? payment.getPaidAt() : "").append(',');
            sb.append(payment.getValidFrom() != null ? payment.getValidFrom() : "").append(',');
            sb.append(payment.getValidUntil() != null ? payment.getValidUntil() : "").append(',');
            sb.append(payment.getNote() != null ? payment.getNote().replace(",", "，") : "");
            sb.append('\n');
        }

        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 自分の支払い記録をページング取得する。
     */
    public Page<MemberPaymentResponse> listMyPayments(Long userId, Pageable pageable) {
        return memberPaymentRepository.findByUserIdOrderByPaidAtDescCreatedAtDesc(userId, pageable)
                .map(paymentMapper::toMemberPaymentResponse);
    }

    /**
     * Stripe 手動再同期を実行する。
     */
    @Transactional
    public ReconcileResponse reconcile(Long paymentId) {
        MemberPaymentEntity entity = memberPaymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        if (entity.getPaymentMethod() == PaymentMethod.MANUAL) {
            throw new BusinessException(PaymentErrorCode.STRIPE_PAYMENT_ONLY);
        }

        String previousStatus = entity.getStatus().name();
        StripePaymentProvider.SessionStatusInfo statusInfo =
                stripePaymentProvider.retrieveSessionStatus(entity.getStripeCheckoutSessionId());

        boolean reconciled = false;
        if ("succeeded".equals(statusInfo.paymentIntentStatus())
                && entity.getStatus() == PaymentStatus.PENDING) {
            PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(entity.getPaymentItemId());
            LocalDate validFrom = LocalDate.now();
            LocalDate validUntil = calculateValidUntil(paymentItem.getType(), validFrom);
            entity.markAsPaid(statusInfo.paymentIntentId(), entity.getAmountPaid(),
                    validFrom, validUntil, null);
            reconciled = true;
        } else if ("expired".equals(statusInfo.paymentIntentStatus())
                && entity.getStatus() == PaymentStatus.PENDING) {
            entity.markAsCancelled();
            reconciled = true;
        }

        if (reconciled) {
            memberPaymentRepository.save(entity);
        }

        log.info("Stripe 再同期: paymentId={}, reconciled={}", paymentId, reconciled);
        return new ReconcileResponse(paymentId, previousStatus, entity.getStatus().name(),
                statusInfo.paymentIntentStatus(), reconciled);
    }

    /**
     * 有効期限を計算する。
     */
    private LocalDate calculateValidUntil(PaymentItemType type, LocalDate validFrom) {
        return switch (type) {
            case ANNUAL_FEE -> validFrom.plusDays(365);
            case MONTHLY_FEE -> validFrom.plusDays(31);
            case ITEM, DONATION -> null;
        };
    }
}
