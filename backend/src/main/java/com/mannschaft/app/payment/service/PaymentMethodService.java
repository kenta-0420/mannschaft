package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F08.9 P5 第二波: 支払い方法（SetupIntent）基盤サービス（設計書 02 §4.1）。
 *
 * <p>継続課金（subscribe・案b）は次サイクル以降を off_session（カード保持者不在）で課金するため、加入前に
 * SetupIntent でカードを保存し、payer の Stripe Customer の既定 PaymentMethod として焼き付ける必要がある。
 * 本サービスは 2 つの責務を持つ:</p>
 * <ol>
 *   <li>{@link #createSetupIntent(Long)} — 認証ユーザーの Customer を get-or-create し SetupIntent の
 *       {@code client_secret} を返す（FE が Stripe.js で confirm・カード直送・PCI SAQ-A）。</li>
 *   <li>{@link #confirmPaymentMethod(Long, String)} — FE で confirm 済みの {@code payment_method_id} を
 *       Customer へ attach＋既定設定し、{@code stripe_customers.default_payment_method} を更新する。</li>
 * </ol>
 *
 * <p><b>email プレースホルダの既知負債（P1 踏襲）:</b> Customer 新規作成時に Stripe へ渡す email は
 * P1（{@code MemberPaymentService.getOrCreateStripeCustomer}）と同一のプレースホルダ {@code "user@example.com"}
 * を用いる。実メール反映は P1 側の既知負債として別途修正対象（本波では P1 と挙動を揃え、直さない）。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §4.1</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentMethodService {

    private final StripeCustomerRepository stripeCustomerRepository;
    private final StripePaymentProvider stripePaymentProvider;

    /**
     * 認証ユーザーの Stripe Customer を get-or-create し、off_session 用 SetupIntent を作成する（設計書 02 §4.1）。
     *
     * @param userId 認証ユーザー ID（呼出側で SecurityUtils 解決）
     * @return SetupIntent 情報（id / clientSecret / status）
     */
    @Transactional
    public StripePaymentProvider.SetupIntentInfo createSetupIntent(Long userId) {
        StripeCustomerEntity customer = getOrCreateStripeCustomer(userId);
        StripePaymentProvider.SetupIntentInfo info =
                stripePaymentProvider.createSetupIntent(customer.getStripeCustomerId());
        log.info("SetupIntent 作成: userId={}, customer={}, setupIntentId={}",
                userId, customer.getStripeCustomerId(), info.setupIntentId());
        return info;
    }

    /**
     * confirm 済みの PaymentMethod を attach＋既定設定し、{@code stripe_customers.default_payment_method} を更新する
     * （設計書 02 §4.1）。
     *
     * @param userId          認証ユーザー ID（呼出側で SecurityUtils 解決）
     * @param paymentMethodId FE で confirm 済みの PaymentMethod ID（{@code pm_xxx}）
     * @return 更新後の Stripe Customer（default_payment_method に pm がセット済み）
     */
    @Transactional
    public StripeCustomerEntity confirmPaymentMethod(Long userId, String paymentMethodId) {
        StripeCustomerEntity customer = getOrCreateStripeCustomer(userId);
        // Stripe 先（attach＋default 設定）・DB 後（default_payment_method 焼付）。
        stripePaymentProvider.attachPaymentMethodAndSetDefault(customer.getStripeCustomerId(), paymentMethodId);
        customer.setDefaultPaymentMethod(paymentMethodId);
        StripeCustomerEntity saved = stripeCustomerRepository.save(customer);
        log.info("PaymentMethod confirm（attach＋default 焼付）: userId={}, customer={}, pm={}",
                userId, customer.getStripeCustomerId(), paymentMethodId);
        return saved;
    }

    /**
     * ユーザーの Stripe Customer を取得、無ければ作成する（P1 {@code MemberPaymentService.getOrCreateStripeCustomer} と
     * 同一挙動・email プレースホルダは P1 既知負債を踏襲して直さない）。
     */
    private StripeCustomerEntity getOrCreateStripeCustomer(Long userId) {
        return stripeCustomerRepository.findByUserId(userId)
                .orElseGet(() -> {
                    String customerId = stripePaymentProvider.createCustomer("user@example.com", userId);
                    return stripeCustomerRepository.save(StripeCustomerEntity.builder()
                            .userId(userId)
                            .stripeCustomerId(customerId)
                            .build());
                });
    }
}
