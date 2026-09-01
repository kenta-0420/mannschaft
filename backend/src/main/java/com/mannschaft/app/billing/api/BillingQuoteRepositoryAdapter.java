package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BillingQuoteRepository} の JPA 実装。
 *
 * <p>金額（初回日割り・翌月満額）は {@code amount_snapshot} JSON へ焼き付ける。消費は
 * {@link BillingQuoteJpaRepository#consumeIfUnchanged} の CAS だけで行い、read-modify-write はしない。</p>
 */
@Component
@RequiredArgsConstructor
class BillingQuoteRepositoryAdapter implements BillingQuoteRepository {

    /** {@code amount_snapshot} JSON の構造（初回=日割り後・翌月=満額）。 */
    public record AmountSnapshot(BillingMoney initialTotal, BillingMoney nextMonthlyTotal) { }

    private final BillingQuoteJpaRepository quoteJpaRepository;
    private final BillingPriceBandVersionRepository priceBandVersionRepository;
    private final ObjectMapper objectMapper;

    @Override
    public BillingQuoteSnapshot save(BillingQuoteSnapshot quote) {
        BillingQuoteEntity entity = BillingQuoteEntity.builder()
                .actorId(quote.actorId())
                .billingCustomerId(quote.billingCustomerId())
                .organizationId(quote.scopeKind() == EntitlementScopeKind.ORG ? quote.scopeId() : null)
                .scopeKind(quote.scopeKind())
                .scopeId(quote.scopeId())
                .productKind(quote.productKind())
                .productKey(quote.productKey())
                .priceBandVersionId(quote.priceBandVersionId())
                .memberCount(quote.memberCount())
                .taxSnapshot(quote.taxSnapshot())
                .amountSnapshot(writeAmounts(quote))
                .periodStart(quote.periodStart())
                .periodEnd(quote.periodEnd())
                .prorationAt(quote.prorationAt())
                .contractVersion(quote.contractVersion())
                .requestHash(quote.requestHash())
                .expiresAt(quote.expiresAt())
                .consumedAt(quote.consumedAt())
                .version(0L)
                .build();
        return toSnapshot(quoteJpaRepository.saveAndFlush(entity), quote.stripePriceRef());
    }

    @Override
    public Optional<BillingQuoteSnapshot> findById(UUID quoteId) {
        if (quoteId == null) {
            return Optional.empty();
        }
        return quoteJpaRepository.findByIdAndDeletedAtIsNull(quoteId)
                .map(entity -> toSnapshot(entity, resolveStripePriceRef(entity.getPriceBandVersionId())));
    }

    @Override
    public int consumeIfUnchanged(UUID quoteId, long actorId, long version, Instant now) {
        if (quoteId == null || now == null) {
            return 0;
        }
        return quoteJpaRepository.consumeIfUnchanged(quoteId, actorId, version, now);
    }

    /** quote 表は Stripe Price を持たない（正本は price band）ため、読み出し時は band から解決する。 */
    private String resolveStripePriceRef(UUID priceBandVersionId) {
        return priceBandVersionRepository.findByIdAndDeletedAtIsNull(priceBandVersionId)
                .map(BillingPriceBandVersionEntity::getStripePriceRef)
                .orElse(null);
    }

    /**
     * @param stripePriceRef 当該 quote の price band が指す Stripe Price
     */
    private BillingQuoteSnapshot toSnapshot(BillingQuoteEntity entity, String stripePriceRef) {
        AmountSnapshot amounts = readAmounts(entity.getAmountSnapshot());
        return new BillingQuoteSnapshot(
                entity.getId(), entity.getActorId(), entity.getScopeKind(), entity.getScopeId(),
                entity.getBillingCustomerId(), entity.getProductKind(), entity.getProductKey(),
                entity.getPriceBandVersionId(), stripePriceRef, entity.getMemberCount(),
                amounts.initialTotal(), amounts.nextMonthlyTotal(), entity.getTaxSnapshot(),
                entity.getPeriodStart(), entity.getPeriodEnd(), entity.getProrationAt(),
                entity.getContractVersion(), entity.getRequestHash(), entity.getExpiresAt(),
                entity.getConsumedAt(), entity.getVersion() == null ? 0L : entity.getVersion());
    }

    private String writeAmounts(BillingQuoteSnapshot quote) {
        try {
            return objectMapper.writeValueAsString(
                    new AmountSnapshot(quote.initialTotal(), quote.nextMonthlyTotal()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize billing quote amount snapshot", e);
        }
    }

    private AmountSnapshot readAmounts(String json) {
        try {
            return objectMapper.readValue(json, AmountSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize billing quote amount snapshot", e);
        }
    }
}
