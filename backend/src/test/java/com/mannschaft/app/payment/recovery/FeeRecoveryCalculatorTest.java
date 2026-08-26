package com.mannschaft.app.payment.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F22.1 謝礼決済 §6.3 第四陣 A: {@link FeeRecoveryCalculator} 純粋関数の単体テスト（境界網羅）。
 *
 * <p>test-first。全額回収／部分回収＋繰越／headroom=0／負・ゼロ安全を固定し、
 * <b>上乗せ後 application_fee = totalFee + recovery ≤ amount（chk_et_fee）が常に成立</b>することを保証する。</p>
 */
@DisplayName("FeeRecoveryCalculator 純粋関数（次回入金相殺の上乗せ回収額・chk_et_fee 不可侵）")
class FeeRecoveryCalculatorTest {

    /** headroom = amount − totalFee。額面 10,000・DEFAULT policy なら amount=10,250 / totalFee=500 → headroom=9,750。 */
    private static final long AMOUNT = 10_250L;
    private static final long TOTAL_FEE = 500L;
    private static final long HEADROOM = AMOUNT - TOTAL_FEE; // 9,750

    @Test
    @DisplayName("outstanding ≤ headroom: 全額回収（outstanding をそのまま上乗せ）")
    void outstandingWithinHeadroom_fullRecovery() {
        long recovery = FeeRecoveryCalculator.recoveryToApply(369L, AMOUNT, TOTAL_FEE);
        assertThat(recovery).isEqualTo(369L);
        assertChkEtFee(recovery);
    }

    @Test
    @DisplayName("outstanding == headroom ちょうど: headroom 全部を回収（境界・全額）")
    void outstandingEqualsHeadroom_recoversAll() {
        long recovery = FeeRecoveryCalculator.recoveryToApply(HEADROOM, AMOUNT, TOTAL_FEE);
        assertThat(recovery).isEqualTo(HEADROOM);
        assertChkEtFee(recovery);
        // 上乗せ後 application_fee = totalFee + headroom = amount（上限ちょうど・chk_et_fee 等号）。
        assertThat(TOTAL_FEE + recovery).isEqualTo(AMOUNT);
    }

    @Test
    @DisplayName("outstanding > headroom: 部分回収（headroom 分のみ・残りは繰越）")
    void outstandingExceedsHeadroom_partialRecoveryWithCarryover() {
        long outstanding = HEADROOM + 5_000L; // 14,750（headroom 超過）
        long recovery = FeeRecoveryCalculator.recoveryToApply(outstanding, AMOUNT, TOTAL_FEE);
        // 今回は headroom（9,750）のみ回収。
        assertThat(recovery).isEqualTo(HEADROOM);
        // 繰越（次回へ残る分）= outstanding − recovery = 5,000。
        assertThat(outstanding - recovery).isEqualTo(5_000L);
        assertChkEtFee(recovery);
    }

    @Test
    @DisplayName("headroom = 0（totalFee == amount の極端ケース）: 回収 0")
    void zeroHeadroom_noRecovery() {
        long recovery = FeeRecoveryCalculator.recoveryToApply(1_000L, 500L, 500L);
        assertThat(recovery).isZero();
    }

    @Test
    @DisplayName("headroom < 0（totalFee > amount・理論上 C060 で弾かれるが防御）: 回収 0（chk_et_fee 不可侵）")
    void negativeHeadroom_noRecovery() {
        long recovery = FeeRecoveryCalculator.recoveryToApply(1_000L, 100L, 500L);
        assertThat(recovery).isZero();
    }

    @Test
    @DisplayName("outstanding = 0: 回収 0（未回収残高なし＝通常 charge と完全不変）")
    void zeroOutstanding_noRecovery() {
        long recovery = FeeRecoveryCalculator.recoveryToApply(0L, AMOUNT, TOTAL_FEE);
        assertThat(recovery).isZero();
    }

    @Test
    @DisplayName("outstanding < 0（過回収/調整で符号反転した残高）: 回収 0（安全クランプ）")
    void negativeOutstanding_noRecovery() {
        long recovery = FeeRecoveryCalculator.recoveryToApply(-200L, AMOUNT, TOTAL_FEE);
        assertThat(recovery).isZero();
    }

    @Test
    @DisplayName("totalFee = 0（手数料 0 policy）: headroom = amount 全部・outstanding 全額回収可")
    void zeroTotalFee_headroomIsFullAmount() {
        long recovery = FeeRecoveryCalculator.recoveryToApply(3_000L, AMOUNT, 0L);
        assertThat(recovery).isEqualTo(3_000L);
        // application_fee = 0 + 3,000 = 3,000 ≤ amount(10,250)。
        assertThat(0L + recovery).isLessThanOrEqualTo(AMOUNT);
    }

    /**
     * 上乗せ後 application_fee = totalFee + recovery が請求額を超えない（chk_et_fee）ことを検証する補助 assert。
     */
    private void assertChkEtFee(long recovery) {
        assertThat(TOTAL_FEE + recovery).isLessThanOrEqualTo(AMOUNT);
    }
}
