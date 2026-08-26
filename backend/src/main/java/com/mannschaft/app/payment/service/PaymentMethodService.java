package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentErrorCode;
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
 * <p><b>【残債2】email プレースホルダの根治（P1 既知負債の解消）:</b> Customer 新規作成時に Stripe へ渡す email は
 * 実決済開始後は Stripe の領収書送付先になるため、固定プレースホルダ {@code "user@example.com"} ではなく
 * {@link MembershipSubscriptionService#resolveEmailForStripeCustomer(Long)} 経由で実メールを取得する
 * （P1 の {@code MemberPaymentService.getOrCreateStripeCustomer} 側のプレースホルダは本修正のスコープ外・
 * 既存 Customer の email 更新もスコープ外・新規作成時のみ改善）。</p>
 *
 * <p><b>退会済み/不在ユーザーの扱い:</b> {@link #getOrCreateStripeCustomer(Long)} は対象ユーザーが
 * 退会済み（{@code deletedAt} 非 null）または存在しない場合、プレースホルダで通さず
 * {@link BusinessException}({@link PaymentErrorCode#STRIPE_CUSTOMER_TARGET_USER_WITHDRAWN}) を投げて
 * Customer 新規作成そのものを拒否する。理由（judgement・2026-07-11）:</p>
 * <ul>
 *   <li>退会 30 日後の物理削除バッチ（{@code AccountPurgeService}）＋{@code BillingPurgeEventListener} が
 *       猶予終了時に USER スコープの契約を強制解約するため、退会受付後に新規 Customer/決済導線が生きている
 *       状態は業務的に想定外（呼出元の認可・画面導線が正しく塞いでいれば到達しないはずの防御的分岐）。</li>
 *   <li>プレースホルダ許容だと「間もなく物理削除されるユーザー」の孤児 Stripe Customer をわざわざ新規作成
 *       してしまい、実害のない Stripe リソースの無駄・調査時のノイズを増やすだけで得るものがない。</li>
 *   <li>「到達したら即例外で気づける」方が、症状を隠すプレースホルダ運用よりも根治的
 *       （CLAUDE.md 障害対応の原則）。</li>
 * </ul>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §4.1</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentMethodService {

    private final StripeCustomerRepository stripeCustomerRepository;
    private final StripePaymentProvider stripePaymentProvider;
    /** 【残債2】ユーザー実メール解決のため注入（payment ドメイン内・既存の凍結済み UserEntity 参照範囲）。 */
    private final MembershipSubscriptionService membershipSubscriptionService;

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
     * ユーザーの Stripe Customer を get-or-create し、その ID（{@code cus_xxx}）を返す
     * （F20.1 billing 等の<b>他ドメイン向け公開 API</b>・ArchUnit D-1 対応）。
     *
     * <p>クロスドメイン Entity 依存の禁止（CLAUDE.md「ドメイン間のデータ取得は Service のメソッド呼び出し経由」・
     * {@code CrossDomainEntityImportArchTest}）のため、{@link StripeCustomerEntity} ではなく ID 文字列を返す。
     * get-or-create の実体は {@link #getOrCreateStripeCustomer(Long)}（残債2: 実メール解決を含め
     * payment ドメイン内に集約）。</p>
     *
     * @param userId 対象ユーザー ID
     * @return Stripe Customer ID（{@code cus_xxx}）
     */
    @Transactional
    public String getOrCreateStripeCustomerId(Long userId) {
        return getOrCreateStripeCustomer(userId).getStripeCustomerId();
    }

    /**
     * ユーザーの Stripe Customer を取得、無ければ作成する。
     *
     * <p>【残債2】新規作成時は {@link MembershipSubscriptionService#resolveEmailForStripeCustomer(Long)}
     * で解決した実メールを Stripe へ渡す（領収書送付先）。対象ユーザーが退会済み/不在の場合は
     * {@link BusinessException}({@link PaymentErrorCode#STRIPE_CUSTOMER_TARGET_USER_WITHDRAWN}) で
     * Customer 新規作成自体を拒否する（判断理由はクラス Javadoc 参照）。既存 Customer が既にある場合は
     * このメール解決処理は通らない（get-or-create の get 経路）。</p>
     */
    private StripeCustomerEntity getOrCreateStripeCustomer(Long userId) {
        return stripeCustomerRepository.findByUserId(userId)
                .orElseGet(() -> {
                    String email = membershipSubscriptionService.resolveEmailForStripeCustomer(userId)
                            .orElseThrow(() -> new BusinessException(
                                    PaymentErrorCode.STRIPE_CUSTOMER_TARGET_USER_WITHDRAWN));
                    String customerId = stripePaymentProvider.createCustomer(email, userId);
                    return stripeCustomerRepository.save(StripeCustomerEntity.builder()
                            .userId(userId)
                            .stripeCustomerId(customerId)
                            .build());
                });
    }
}
