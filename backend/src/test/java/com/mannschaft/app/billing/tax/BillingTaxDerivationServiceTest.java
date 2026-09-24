package com.mannschaft.app.billing.tax;

import com.mannschaft.app.billing.BillingTaxBehavior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 価格改定戦役 第4隊: {@link BillingTaxDerivationService}（税導出・決定8）の試練（試練・税導出）。
 *
 * <p>導出は revision/band の create トランザクション内で、band の {@code effectiveFrom} 基準で
 * 解決した {@link BillingTaxCodeEntity} を用いて行う（Provision 時の再導出はしない・決定8）。
 * {@link BillingTaxDerivationService} は本試練時点で未実装であり、クラス自体が存在しないため
 * コンパイルエラーとして red になることを是とする。</p>
 *
 * <p>正本: `.claude/campaigns/price-rev-plan-v3.md` 決定8・AC-28・AC-39・AC-40・AC-41。</p>
 */
@DisplayName("BillingTaxDerivationService 試練（AC-28・AC-39・AC-40・AC-41）")
class BillingTaxDerivationServiceTest {

    private final BillingTaxDerivationService service = new BillingTaxDerivationService();

    private BillingTaxCodeView taxCode(int rateBasisPoints) {
        return BillingTaxCodeView.from(BillingTaxCodeEntity.builder()
                .code("JP_STANDARD_10")
                .displayName("標準税率10%")
                .rateBasisPoints(rateBasisPoints)
                .validFrom(Instant.EPOCH)
                .enabled(true)
                .build());
    }

    @Test
    @DisplayName("AC-41: EXCLUSIVE 100円・rate=1000bp → tax=floor(100*1000/10000)=10、excl+tax=incl")
    void ac41_exclusive_100yen_1000bp() {
        BillingTaxDerivationResult r = service.derive(100L, BillingTaxBehavior.EXCLUSIVE, taxCode(1000));
        assertThat(r.getAmountExcludingTax()).isEqualTo(100L);
        assertThat(r.getTaxAmount()).isEqualTo(10L);
        assertThat(r.getAmountIncludingTax()).isEqualTo(110L);
        assertThat(r.getAmountExcludingTax() + r.getTaxAmount()).isEqualTo(r.getAmountIncludingTax());
    }

    @Test
    @DisplayName("AC-41: EXCLUSIVE 3円・rate=1000bp → tax=floor(3*1000/10000)=0（端数切り捨て）")
    void ac41_exclusive_3yen_roundsDownToZeroTax() {
        BillingTaxDerivationResult r = service.derive(3L, BillingTaxBehavior.EXCLUSIVE, taxCode(1000));
        assertThat(r.getTaxAmount()).isEqualTo(0L);
        assertThat(r.getAmountIncludingTax()).isEqualTo(3L);
        assertThat(r.getAmountExcludingTax() + r.getTaxAmount()).isEqualTo(r.getAmountIncludingTax());
    }

    @Test
    @DisplayName("AC-41: EXCLUSIVE 1円・rate=800bp → tax=floor(1*800/10000)=0")
    void ac41_exclusive_1yen_800bp() {
        BillingTaxDerivationResult r = service.derive(1L, BillingTaxBehavior.EXCLUSIVE, taxCode(800));
        assertThat(r.getTaxAmount()).isEqualTo(0L);
        assertThat(r.getAmountExcludingTax()).isEqualTo(1L);
        assertThat(r.getAmountIncludingTax()).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-41: INCLUSIVE 100円・rate=1000bp → excl=floor(100*10000/11000)=90, tax=10, incl=100")
    void ac41_inclusive_100yen_1000bp() {
        BillingTaxDerivationResult r = service.derive(100L, BillingTaxBehavior.INCLUSIVE, taxCode(1000));
        assertThat(r.getAmountIncludingTax()).isEqualTo(100L);
        assertThat(r.getAmountExcludingTax()).isEqualTo(90L);
        assertThat(r.getTaxAmount()).isEqualTo(10L);
        assertThat(r.getAmountExcludingTax() + r.getTaxAmount()).isEqualTo(r.getAmountIncludingTax());
    }

    @Test
    @DisplayName("AC-28: inputAmount の上限は 9,999,999。上限ちょうどで long を溢れさせない")
    void ac28_maxAmountBoundary_noOverflow() {
        long max = 9_999_999L;
        BillingTaxDerivationResult r = service.derive(max, BillingTaxBehavior.EXCLUSIVE, taxCode(1000));
        assertThat(r.getAmountExcludingTax()).isEqualTo(max);
        assertThat(r.getTaxAmount()).isEqualTo(max * 1000 / 10000);
        assertThat(r.getAmountIncludingTax()).isEqualTo(r.getAmountExcludingTax() + r.getTaxAmount());
        assertThat(r.getAmountIncludingTax()).isPositive();
    }

    @Test
    @DisplayName("AC-28: inputAmount 上限超過（10,000,000）は 400 相当の例外")
    void ac28_overMaxAmount_rejected() {
        assertThatThrownBy(() -> service.derive(10_000_000L, BillingTaxBehavior.EXCLUSIVE, taxCode(1000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC-39: EXCLUSIVE の場合 is_included_in_price=false と整合する")
    void ac39_exclusiveMapsToIncludedInPriceFalse() {
        BillingTaxDerivationResult r = service.derive(100L, BillingTaxBehavior.EXCLUSIVE, taxCode(1000));
        assertThat(r.isIncludedInPrice()).isFalse();
    }

    @Test
    @DisplayName("AC-39: INCLUSIVE の場合 is_included_in_price=true と整合する")
    void ac39_inclusiveMapsToIncludedInPriceTrue() {
        BillingTaxDerivationResult r = service.derive(100L, BillingTaxBehavior.INCLUSIVE, taxCode(1000));
        assertThat(r.isIncludedInPrice()).isTrue();
    }

    @Test
    @DisplayName("AC-40: 導出結果は amountExcludingTax/taxAmount/taxRateBasisPoints/taxNameSnapshot/amountIncludingTax を保持する")
    void ac40_resultHoldsAllSnapshotFields() {
        BillingTaxCodeView code = taxCode(1000);
        BillingTaxDerivationResult r = service.derive(100L, BillingTaxBehavior.EXCLUSIVE, code);

        assertThat(r.getAmountExcludingTax()).isNotNull();
        assertThat(r.getTaxAmount()).isNotNull();
        assertThat(r.getTaxRateBasisPoints()).isEqualTo(1000);
        assertThat(r.getTaxNameSnapshot()).isEqualTo(code.displayName());
        assertThat(r.getAmountIncludingTax()).isNotNull();
        assertThat(r.getTaxCodeSnapshot()).isEqualTo(code.code());
        assertThat(r.getTaxMasterSnapshot()).isNotBlank();
    }

    @Test
    @DisplayName("AC-27: inputAmount<=0 は作成時点で拒否される")
    void ac27_nonPositiveAmount_rejected() {
        assertThatThrownBy(() -> service.derive(0L, BillingTaxBehavior.EXCLUSIVE, taxCode(1000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.derive(-1L, BillingTaxBehavior.EXCLUSIVE, taxCode(1000)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
