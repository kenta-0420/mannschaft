package com.mannschaft.app.notification.credit.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.credit.dto.NotificationCreditCheckoutResponse;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPackageEntity;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseEntity;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseStatus;
import com.mannschaft.app.notification.credit.error.NotificationCreditErrorCode;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPackageRepository;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPurchaseRepository;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * F09.13 通知プリペイドクレジット Checkout サービス。
 *
 * <p>Stripe Checkout Session の作成と Webhook による購入完了処理を担当する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationCreditCheckoutService {

    private final NotificationCreditPackageRepository packageRepository;
    private final NotificationCreditPurchaseRepository purchaseRepository;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final StripePaymentProvider stripePaymentProvider;
    private final NotificationCreditService creditService;
    private final AuditLogService auditLogService;
    private final UserRepository userRepository;

    @Value("${app.base-url}")
    private String appBaseUrl;

    // ─────────────────────────────────────────────────────────
    // Checkout セッション作成
    // ─────────────────────────────────────────────────────────

    /**
     * 通知クレジット購入用 Stripe Checkout Session を作成する。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>パッケージ取得・検証</li>
     *   <li>{@code stripe_price_id} が null の場合は Stripe へ Product/Price を遅延登録</li>
     *   <li>購入ユーザーの Stripe Customer を取得 or 生成</li>
     *   <li>{@code notification_credit_purchases} に PENDING レコードを作成</li>
     *   <li>Stripe Checkout Session を作成し URL を返す</li>
     * </ol>
     *
     * @param orgId     購入対象組織ID
     * @param packageId 購入するパッケージID
     * @param userId    購入操作者のユーザーID
     * @return Checkout セッション情報（URL・SessionID）
     */
    @Transactional
    public NotificationCreditCheckoutResponse createCheckout(Long orgId, Long packageId, Long userId) {
        // パッケージ取得
        NotificationCreditPackageEntity pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> new BusinessException(NotificationCreditErrorCode.CREDIT_PACKAGE_NOT_FOUND));

        // Stripe Price の遅延生成
        if (pkg.getStripePriceId() == null) {
            try {
                String stripeProductId = stripePaymentProvider.createProduct(
                        "通知クレジット: " + pkg.getName(), pkg.getId());
                String stripePriceId = stripePaymentProvider.createPrice(
                        stripeProductId, pkg.getPriceJpy(), "JPY");
                pkg.setStripePriceId(stripePriceId);
                packageRepository.save(pkg);
                log.info("Stripe Price 遅延生成: packageId={}, priceId={}", packageId, stripePriceId);
            } catch (Exception e) {
                log.error("Stripe Price 遅延生成失敗: packageId={}", packageId, e);
                throw new BusinessException(NotificationCreditErrorCode.CHECKOUT_FAILED);
            }
        }

        // 購入ユーザーの Stripe Customer 取得 or 生成
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_999));

        StripeCustomerEntity stripeCustomer = stripeCustomerRepository.findByUserId(userId)
                .orElseGet(() -> {
                    String stripeCustomerId = stripePaymentProvider.createCustomer(
                            user.getEmail(), userId);
                    StripeCustomerEntity newCustomer = StripeCustomerEntity.builder()
                            .userId(userId)
                            .stripeCustomerId(stripeCustomerId)
                            .build();
                    return stripeCustomerRepository.save(newCustomer);
                });

        // 購入レコードを PENDING で作成
        NotificationCreditPurchaseEntity purchase = NotificationCreditPurchaseEntity.builder()
                .organizationId(orgId)
                .packageId(pkg.getId())
                .purchasedByUserId(userId)
                .creditsGranted(pkg.getCredits())
                .remainingCredits(0L) // 購入完了後に credits_granted にセットされる
                .priceJpy(pkg.getPriceJpy())
                .paymentStatus(NotificationCreditPurchaseStatus.PENDING)
                .idempotencyKey(UUID.randomUUID().toString())
                .build();
        purchase = purchaseRepository.save(purchase);

        // Stripe Checkout Session 作成
        try {
            String successUrl = appBaseUrl + "/organizations/" + orgId
                    + "/settings/notification-credits?payment=success";
            String cancelUrl = appBaseUrl + "/organizations/" + orgId
                    + "/settings/notification-credits?payment=cancelled";

            StripePaymentProvider.CheckoutSessionInfo sessionInfo =
                    stripePaymentProvider.createNotificationCreditCheckoutSession(
                            pkg.getStripePriceId(),
                            stripeCustomer.getStripeCustomerId(),
                            purchase.getId(),
                            successUrl,
                            cancelUrl
                    );

            // Stripe Session ID を購入レコードに保存
            purchase = purchase.toBuilder()
                    .stripeCheckoutSessionId(sessionInfo.sessionId())
                    .build();
            purchaseRepository.save(purchase);

            log.info("通知クレジット Checkout Session 作成: orgId={}, packageId={}, purchaseId={}, sessionId={}",
                    orgId, packageId, purchase.getId(), sessionInfo.sessionId());

            return new NotificationCreditCheckoutResponse(sessionInfo.checkoutUrl(), sessionInfo.sessionId());

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Stripe Checkout Session 作成失敗: packageId={}, orgId={}", packageId, orgId, e);
            throw new BusinessException(NotificationCreditErrorCode.CHECKOUT_FAILED);
        }
    }

    // ─────────────────────────────────────────────────────────
    // Webhook: 購入完了処理
    // ─────────────────────────────────────────────────────────

    /**
     * {@code checkout.session.completed} Webhook イベントから購入完了を処理する。
     *
     * <p>冪等性: {@code idempotency_key} の UNIQUE 制約により二重処理を防ぐ。
     * 既に PAID 済みの購入レコードはスキップする。</p>
     *
     * @param event Webhook イベント情報（{@code notificationCreditPurchaseId} を含む）
     */
    @Transactional
    public void handlePurchaseCompleted(StripePaymentProvider.WebhookEventInfo event) {
        if (event.notificationCreditPurchaseId() == null) {
            log.warn("notificationCreditPurchaseId が metadata に含まれていません");
            return;
        }

        NotificationCreditPurchaseEntity purchase =
                purchaseRepository.findById(event.notificationCreditPurchaseId()).orElse(null);
        if (purchase == null) {
            log.warn("通知クレジット購入記録が見つかりません: purchaseId={}", event.notificationCreditPurchaseId());
            return;
        }

        // 冪等処理: PAID 済みはスキップ
        if (purchase.getPaymentStatus() == NotificationCreditPurchaseStatus.PAID) {
            log.info("既にPAID済み。スキップ: purchaseId={}", purchase.getId());
            return;
        }

        // 購入レコードをPAIDに更新
        purchase.markAsPaid(event.paymentIntentId(), event.receiptUrl());
        purchaseRepository.save(purchase);

        // クレジット残高に加算
        creditService.addCredits(purchase.getId());

        // 監査ログ記録
        auditLogService.record(
                AuditEventType.NOTIFICATION_CREDIT_PURCHASED.name(),
                purchase.getPurchasedByUserId(),
                null,
                null,
                purchase.getOrganizationId(),
                null, null, null,
                "{\"purchaseId\":" + purchase.getId()
                        + ",\"credits\":" + purchase.getCreditsGranted()
                        + ",\"priceJpy\":" + purchase.getPriceJpy() + "}"
        );

        log.info("通知クレジット購入完了: purchaseId={}, organizationId={}, credits={}",
                purchase.getId(), purchase.getOrganizationId(), purchase.getCreditsGranted());
    }
}
