package com.mannschaft.app.billing.invoice;

import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link StripeSubscriptionMetadataVerifier} の Stripe API 実装。
 *
 * <p>取得失敗（ネットワーク・権限・未存在）は<b>握り潰して no-op にはしない</b>が、
 * ここで例外を投げると「所有判定のための問い合わせ」が webhook 全体を落としてしまうため、
 * WARN を残して {@link Optional#empty()}（＝紐付けない・fail-closed）を返す。
 * 呼び出し元は所有と断定できないので確定させず、Stripe の再送で再判定される。</p>
 */
@Slf4j
@Component
public class StripeSubscriptionMetadataVerifierImpl implements StripeSubscriptionMetadataVerifier {

    /** Checkout Session / Subscription に焼き付ける自プラットフォームの契約 ID キー。 */
    static final String METADATA_KEY = "billingContractId";

    @Override
    public Optional<UUID> resolveBillingContractId(String subscriptionRef) {
        if (subscriptionRef == null || subscriptionRef.isBlank()) {
            return Optional.empty();
        }
        try {
            Subscription subscription = Subscription.retrieve(subscriptionRef);
            Map<String, String> metadata = subscription == null ? null : subscription.getMetadata();
            String raw = metadata == null ? null : metadata.get(METADATA_KEY);
            if (raw == null || raw.isBlank()) {
                log.info("F20.1 PR5 AC-8: Subscription の metadata に {} が無いため billing へ紐付けません: sub={}",
                        METADATA_KEY, subscriptionRef);
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException e) {
            log.warn("F20.1 PR5 AC-8: metadata.{} が UUID として解釈できないため紐付けません: sub={}",
                    METADATA_KEY, subscriptionRef, e);
            return Optional.empty();
        } catch (StripeException e) {
            log.warn("F20.1 PR5 AC-8: Subscription の取得に失敗したため所有と断定しません（再送で再判定）: sub={}",
                    subscriptionRef, e);
            return Optional.empty();
        }
    }
}
