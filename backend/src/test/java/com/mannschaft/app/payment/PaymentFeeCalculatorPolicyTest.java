package com.mannschaft.app.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F22.1 市（Market）統一決済 R1: 手数料ランク化（policy 注入）の {@link PaymentFeeCalculator} 単体テスト。
 *
 * <p>設計書 02 §3.5 / §3.5.4 / §3.5.5（マスター確定・2026-06-04）。test-first。</p>
 *
 * <p>計算規約（折半50/50固定）:</p>
 * <pre>
 * total_fee             = round(percent_rate × face) + flat_fee_minor
 * half_fee              = round(total_fee ÷ 2)
 * payer_surcharge       = half_fee
 * amount (charge)       = face + half_fee
 * application_fee_amount = total_fee
 * transfer_amount       = amount − total_fee
 * </pre>
 */
class PaymentFeeCalculatorPolicyTest {

    private final PaymentFeeCalculator calculator = new PaymentFeeCalculator();

    private static FeePolicy policy(String percent, long flat) {
        return new FeePolicy("TEST", new BigDecimal(percent), flat);
    }

    @Nested
    @DisplayName("設計書 §3.5.4 DEFAULT（率5%＋固定0）— 既存挙動と完全一致（後方互換）")
    class DefaultPolicy {

        @Test
        @DisplayName("DEFAULT・額面10,000 → total500 / payerFee250 / charge10,250 / appFee500 / transfer9,750")
        void defaultFaceTenThousand() {
            FeeBreakdown b = calculator.calculate(10_000L, FeePolicy.defaultPolicy());

            assertThat(b.faceAmount()).isEqualTo(10_000L);
            assertThat(b.payerFee()).isEqualTo(250L);              // round(500 / 2)
            assertThat(b.chargeAmount()).isEqualTo(10_250L);       // 10,000 + 250（escrow_transactions.amount）
            assertThat(b.applicationFeeAmount()).isEqualTo(500L);  // total_fee = round(0.05 × 10,000) + 0
            assertThat(b.transferAmount()).isEqualTo(9_750L);      // 10,250 − 500
        }

        @Test
        @DisplayName("FeePolicy.defaultPolicy() 経由でも額面10,000→appFee500（DEFAULT_KEY/率0.05）")
        void defaultPolicyConstant() {
            FeePolicy dp = FeePolicy.defaultPolicy();
            assertThat(dp.policyKey()).isEqualTo("DEFAULT");
            assertThat(dp.percentRate()).isEqualByComparingTo(new BigDecimal("0.0500"));
            assertThat(dp.flatFeeMinor()).isEqualTo(0L);

            FeeBreakdown b = calculator.calculate(10_000L, dp);
            assertThat(b.applicationFeeAmount()).isEqualTo(500L);
            assertThat(b.chargeAmount()).isEqualTo(10_250L);
        }
    }

    @Nested
    @DisplayName("設計書 §3.5.5 固定額入りパターン（率3%＋固定100）")
    class FlatFeePolicy {

        @Test
        @DisplayName("率3%＋固定100・額面10,000 → total400 / payerFee200 / charge10,200 / appFee400 / transfer9,800")
        void threePercentPlusOneHundred() {
            FeeBreakdown b = calculator.calculate(10_000L, policy("0.0300", 100L));

            assertThat(b.applicationFeeAmount()).isEqualTo(400L);  // round(0.03 × 10,000) + 100 = 300 + 100
            assertThat(b.payerFee()).isEqualTo(200L);              // round(400 / 2)
            assertThat(b.chargeAmount()).isEqualTo(10_200L);       // 10,000 + 200
            assertThat(b.transferAmount()).isEqualTo(9_800L);      // 10,200 − 400
        }

        @Test
        @DisplayName("固定額のみ（率0%＋固定300）・額面10,000 → total300 / half150 / charge10,150 / transfer9,850")
        void flatOnly() {
            FeeBreakdown b = calculator.calculate(10_000L, policy("0.0000", 300L));
            assertThat(b.applicationFeeAmount()).isEqualTo(300L);
            assertThat(b.payerFee()).isEqualTo(150L);
            assertThat(b.chargeAmount()).isEqualTo(10_150L);
            assertThat(b.transferAmount()).isEqualTo(9_850L);
        }
    }

    @Nested
    @DisplayName("四捨五入（HALF_UP）と恒等式")
    class Rounding {

        @ParameterizedTest(name = "percent={0} flat={1} face={2} → total={3} half={4}")
        @CsvSource({
                // total = round(percent×face)+flat, half = round(total/2)
                "0.0500, 0,   333,  17,  9",   // round(16.65)=17, round(8.5)=9（HALF_UP）
                "0.0300, 100, 333,  110, 55",  // round(9.99)=10 + 100 = 110, round(55)=55
                "0.0250, 0,   20,   1,   1",   // round(0.5)=1, round(0.5)=1
                "0.0000, 1,   100,  1,   1",   // 0 + 1 = 1, round(0.5)=1
        })
        void roundingHalfUp(String percent, long flat, long face, long expectedTotal, long expectedHalf) {
            FeeBreakdown b = calculator.calculate(face, policy(percent, flat));
            assertThat(b.applicationFeeAmount()).as("total_fee").isEqualTo(expectedTotal);
            assertThat(b.payerFee()).as("half_fee").isEqualTo(expectedHalf);
            // 恒等式: charge = face + half, transfer = charge − total。
            assertThat(b.chargeAmount()).isEqualTo(face + expectedHalf);
            assertThat(b.transferAmount()).isEqualTo(b.chargeAmount() - b.applicationFeeAmount());
        }
    }

    @Nested
    @DisplayName("安全ガード（total_fee > face は拒否・§3.5.2）")
    class SafetyGuard {

        @Test
        @DisplayName("固定1,000＋率5%・額面500 → total_fee=1,025 > 500 で IllegalArgumentException")
        void totalFeeExceedsFace() {
            // round(0.05 × 500)=25 + 1,000 = 1,025 > 500
            assertThatThrownBy(() -> calculator.calculate(500L, policy("0.0500", 1_000L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1025");
        }

        @Test
        @DisplayName("total_fee == face は許容（境界・破綻しない）")
        void totalFeeEqualsFaceAllowed() {
            // 固定500・率0%・額面500 → total=500 == face=500 → OK（境界）
            FeeBreakdown b = calculator.calculate(500L, policy("0.0000", 500L));
            assertThat(b.applicationFeeAmount()).isEqualTo(500L);
            assertThat(b.payerFee()).isEqualTo(250L);          // round(500/2)
            assertThat(b.chargeAmount()).isEqualTo(750L);      // 500 + 250
            assertThat(b.transferAmount()).isEqualTo(250L);    // 750 − 500
        }

        @Test
        @DisplayName("appFee ≤ charge（chk_et_fee）は安全ガードにより常に充足")
        void appFeeNeverExceedsCharge() {
            FeeBreakdown b = calculator.calculate(10_000L, policy("0.0300", 100L));
            assertThat(b.applicationFeeAmount()).isLessThanOrEqualTo(b.chargeAmount());
        }
    }

    @Nested
    @DisplayName("不正入力")
    class InvalidInput {

        @Test
        @DisplayName("faceAmount 非正は IllegalArgumentException")
        void nonPositiveFace() {
            assertThatThrownBy(() -> calculator.calculate(0L, FeePolicy.defaultPolicy()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("policy が null は NullPointerException/IllegalArgumentException")
        void nullPolicy() {
            assertThatThrownBy(() -> calculator.calculate(10_000L, (FeePolicy) null))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
