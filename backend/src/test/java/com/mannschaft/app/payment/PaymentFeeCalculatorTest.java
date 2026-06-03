package com.mannschaft.app.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F22.1 市（Market）統一決済 P2-b: 手数料折半計算（5% = 支払者2.5% + 受取側2.5%）の単体テスト。
 *
 * <p>設計書: docs/features/F22.1_market/payment/02_api_design.md §3.5 / §3.5.1（マスター確定・案あ）。
 * test-first（実装 {@link PaymentFeeCalculator} より先に記述）。</p>
 */
class PaymentFeeCalculatorTest {

    private final PaymentFeeCalculator calculator = new PaymentFeeCalculator();

    @Nested
    @DisplayName("設計書 §3.5.1 具体例（額面 10,000 円・JPY）と完全一致")
    class SpecExampleTenThousand {

        @Test
        @DisplayName("額面10,000 → payerFee250 / charge10,250 / appFee500 / transfer9,750 / estStripe369 / estNet131")
        void matchesDesignDocExampleTable() {
            FeeBreakdown b = calculator.calculate(10_000L);

            // 設計02 §3.5.1 の表と1円単位で完全一致すること
            assertThat(b.faceAmount()).isEqualTo(10_000L);
            assertThat(b.payerFee()).isEqualTo(250L);                  // round(10,000 × 0.025)
            assertThat(b.chargeAmount()).isEqualTo(10_250L);           // 10,000 + 250（= escrow_transactions.amount）
            assertThat(b.applicationFeeAmount()).isEqualTo(500L);      // round(10,000 × 0.05)
            assertThat(b.transferAmount()).isEqualTo(9_750L);          // 10,250 − 500
            assertThat(b.estimatedStripeFee()).isEqualTo(369L);        // round(10,250 × 0.036)
            assertThat(b.estimatedNetProfit()).isEqualTo(131L);        // 500 − 369（額面の ≈1.31%）
        }

        @Test
        @DisplayName("純益は額面の約1.31%（131 / 10,000）")
        void netProfitIsAboutOnePointThreeOnePercent() {
            FeeBreakdown b = calculator.calculate(10_000L);
            double ratio = (double) b.estimatedNetProfit() / b.faceAmount();
            assertThat(ratio).isEqualTo(0.0131, org.assertj.core.data.Offset.offset(0.0005));
        }
    }

    @Nested
    @DisplayName("四捨五入（端数）と不変条件")
    class RoundingAndInvariants {

        @Test
        @DisplayName("額面9,999 — 0.5 未満は切り捨て / 0.5 以上は切り上げ（四捨五入）")
        void faceNineThousandNineHundredNinetyNine() {
            FeeBreakdown b = calculator.calculate(9_999L);
            // 9,999 × 0.025 = 249.975 → round → 250
            assertThat(b.payerFee()).isEqualTo(250L);
            assertThat(b.chargeAmount()).isEqualTo(10_249L);
            // 9,999 × 0.05 = 499.95 → round → 500
            assertThat(b.applicationFeeAmount()).isEqualTo(500L);
            assertThat(b.transferAmount()).isEqualTo(9_749L);
        }

        @Test
        @DisplayName("額面1 — 微小額でも四捨五入で 0 円手数料・不変条件維持")
        void faceOne() {
            FeeBreakdown b = calculator.calculate(1L);
            // 1 × 0.025 = 0.025 → round → 0
            assertThat(b.payerFee()).isEqualTo(0L);
            assertThat(b.chargeAmount()).isEqualTo(1L);
            // 1 × 0.05 = 0.05 → round → 0
            assertThat(b.applicationFeeAmount()).isEqualTo(0L);
            assertThat(b.transferAmount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("額面333 — payerFee round(8.325)=8 / appFee round(16.65)=17")
        void faceThreeHundredThirtyThree() {
            FeeBreakdown b = calculator.calculate(333L);
            assertThat(b.payerFee()).isEqualTo(8L);                // 333 × 0.025 = 8.325 → 8
            assertThat(b.chargeAmount()).isEqualTo(341L);          // 333 + 8
            assertThat(b.applicationFeeAmount()).isEqualTo(17L);   // 333 × 0.05 = 16.65 → 17
            assertThat(b.transferAmount()).isEqualTo(324L);        // 341 − 17
        }

        @Test
        @DisplayName("額面20 — round(0.5)=1（半数切り上げ・HALF_UP）")
        void faceTwentyHalfUp() {
            FeeBreakdown b = calculator.calculate(20L);
            // 20 × 0.025 = 0.5 → HALF_UP → 1
            assertThat(b.payerFee()).isEqualTo(1L);
            // 20 × 0.05 = 1.0 → 1
            assertThat(b.applicationFeeAmount()).isEqualTo(1L);
            assertThat(b.chargeAmount()).isEqualTo(21L);
            assertThat(b.transferAmount()).isEqualTo(20L);
        }

        @ParameterizedTest(name = "額面{0}: applicationFeeAmount ≤ chargeAmount（chk_et_fee 整合）")
        @ValueSource(longs = {1L, 20L, 100L, 333L, 999L, 1_000L, 9_999L, 10_000L, 12_345L, 100_000L, 999_999L, 4_294_967_295L})
        void applicationFeeNeverExceedsChargeAmount(long face) {
            FeeBreakdown b = calculator.calculate(face);
            assertThat(b.applicationFeeAmount())
                .as("application_fee_amount(%d) <= amount(%d)", b.applicationFeeAmount(), b.chargeAmount())
                .isLessThanOrEqualTo(b.chargeAmount());
        }

        @ParameterizedTest(name = "額面{0}: chargeAmount = faceAmount + payerFee（恒等）")
        @ValueSource(longs = {1L, 333L, 9_999L, 10_000L, 12_345L})
        void chargeEqualsFacePlusPayerFee(long face) {
            FeeBreakdown b = calculator.calculate(face);
            assertThat(b.chargeAmount()).isEqualTo(b.faceAmount() + b.payerFee());
            assertThat(b.transferAmount()).isEqualTo(b.chargeAmount() - b.applicationFeeAmount());
        }
    }

    @Nested
    @DisplayName("stripeFeeRate のオーバーライド")
    class StripeFeeRateOverride {

        @Test
        @DisplayName("既定 stripeFeeRate は 0.036")
        void defaultRateIsThreePointSix() {
            assertThat(PaymentFeeCalculator.DEFAULT_STRIPE_FEE_RATE).isEqualTo(0.036d);
        }

        @Test
        @DisplayName("stripeFeeRate を渡すと estimatedStripeFee / estimatedNetProfit に反映される")
        void overrideRate() {
            // 額面10,000・rate=0.04 → charge 10,250 → estStripe round(10,250 × 0.04)=410 → net 500-410=90
            FeeBreakdown b = calculator.calculate(10_000L, 0.04d);
            assertThat(b.estimatedStripeFee()).isEqualTo(410L);
            assertThat(b.estimatedNetProfit()).isEqualTo(90L);
            // 額面系は rate に依存しない
            assertThat(b.payerFee()).isEqualTo(250L);
            assertThat(b.applicationFeeAmount()).isEqualTo(500L);
        }
    }

    @Nested
    @DisplayName("不正入力の拒否")
    class InvalidInput {

        @Test
        @DisplayName("0 円は IllegalArgumentException")
        void zeroFaceAmountRejected() {
            assertThatThrownBy(() -> calculator.calculate(0L))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "負値 {0} は IllegalArgumentException")
        @ValueSource(longs = {-1L, -100L, -10_000L, Long.MIN_VALUE})
        void negativeFaceAmountRejected(long face) {
            assertThatThrownBy(() -> calculator.calculate(face))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "stripeFeeRate {0} は不正（0未満/1以上）")
        @CsvSource({"-0.01", "1.0", "1.5"})
        void invalidStripeFeeRateRejected(double rate) {
            assertThatThrownBy(() -> calculator.calculate(10_000L, rate))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
