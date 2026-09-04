package com.mannschaft.app.billing.invoice;

import java.util.Optional;
import java.util.UUID;

/**
 * F20.1 PR5 AC-8: {@code psp_subscription_ref} の DB 逆引きが外れたときに、
 * Stripe の Subscription を取得して {@code metadata.billingContractId} を<b>厳密照合</b>する。
 *
 * <p><b>なぜ DB ヒット単独では足りないのか</b>: 契約作成直後（Checkout 完了 webhook より先に
 * invoice の webhook が届く順不同）や、契約行に subscription ref がまだ焼き付いていない時点では
 * 逆引きが外れる。そこで「Stripe 側の metadata に自プラットフォームの契約 ID が入っているか」を
 * 確かめてから紐付ける。metadata が無い / UUID として解釈できない / 別 scope の契約を指している
 * 場合は<b>紐付けない</b>（fail-closed。緩い一致で他人の請求書を取り込まない）。</p>
 */
public interface StripeSubscriptionMetadataVerifier {

    /**
     * Subscription の metadata から自プラットフォームの契約 ID を取り出す。
     *
     * @param subscriptionRef {@code sub_xxx}
     * @return 契約 ID。metadata が無い・不正・取得できない場合は {@link Optional#empty()}
     */
    Optional<UUID> resolveBillingContractId(String subscriptionRef);
}
