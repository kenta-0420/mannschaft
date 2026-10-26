package com.mannschaft.app.payment.recovery;

/**
 * F22.1 謝礼決済 §6.3 第四陣 A: 次回入金相殺の<b>上乗せ回収額</b>を求める純粋関数（状態なし・DB 非依存）。
 *
 * <p>ModeB 返金で Mannschaft が一時負担した Stripe 実手数料は
 * {@code fee_recovery_balances.outstanding_amount}（payee×通貨単位）に未回収残高として積まれている
 * （第二陣 C1 即時＋第三陣 C2 pending 補完）。本計算は、同じ payee へ次に発生する escrow charge の
 * {@code application_fee_amount} に未回収残高をどれだけ上乗せ（実回収）できるかを求める。</p>
 *
 * <p><b>計算式:</b></p>
 * <pre>
 *   headroom            = amount − totalFee                       // 1 回の上乗せ上限
 *   recoveryToApply     = max(0, min(outstanding, headroom))      // 実際に上乗せする回収額
 * </pre>
 *
 * <p><b>chk_et_fee 不可侵（最重要）:</b> Stripe / DDL / {@code PaymentFeeCalculator} の三重ガード
 * {@code application_fee ≤ amount} を絶対に破らないため、上乗せ上限を {@code headroom = amount − totalFee} に
 * 限定する。上乗せ後の手数料は {@code application_fee = totalFee + recovery ≤ totalFee + headroom = amount} で
 * 常に成立する。{@code amount = face + half_fee} かつ {@code totalFee ≤ face}（C060 保証）より
 * {@code headroom = half_fee + (face − totalFee) ≥ 0} なので headroom は非負である。</p>
 *
 * <p><b>部分回収＋繰越:</b> {@code outstanding > headroom} のときは headroom 分のみ回収し、回収しきれない
 * {@code outstanding − headroom} は残高に残す（次回以降に繰り越す）。{@code headroom = 0}（手数料が請求額と等しい
 * 極端ケース）では回収 0。</p>
 *
 * <p><b>純粋性の隔離:</b> {@code PaymentFeeCalculator}（折半計算の正典・純粋関数）は一切触らない。
 * 回収上乗せは「他者債務の回収」という別概念であり、当該 escrow 自身の {@code face/totalFee/transferAmount} の
 * 計算には混ぜない（混入すると返金時の transferAmount 差分計算が壊れる・隔離原則）。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/02_api_design.md §6.3</p>
 */
public final class FeeRecoveryCalculator {

    private FeeRecoveryCalculator() {
        // ユーティリティ（純粋関数）。インスタンス化しない。
    }

    /**
     * 次回 charge に上乗せできる回収額を求める。
     *
     * <p>負やゼロの入力（{@code outstanding ≤ 0} や {@code amount ≤ totalFee}）でも 0 を返し、
     * {@code application_fee ≤ amount}（chk_et_fee）を決して破らない。</p>
     *
     * @param outstanding 当該 payee×通貨の未回収残高（minor・通常非負だが負・ゼロでも安全に 0 を返す）
     * @param amount      今回 charge の請求額（{@code escrow_transactions.amount}・minor・正値前提）
     * @param totalFee    今回 charge 自身の総手数料（{@code application_fee_amount}・minor・非負前提）
     * @return 上乗せして実回収する額（{@code 0 ≤ recovery ≤ min(outstanding, amount − totalFee)}）
     */
    public static long recoveryToApply(long outstanding, long amount, long totalFee) {
        // 1 回の上乗せ上限（headroom）。負になる入力でも下限 0 でクランプし chk_et_fee を不可侵とする。
        long headroom = amount - totalFee;
        if (headroom <= 0L || outstanding <= 0L) {
            return 0L;
        }
        return Math.min(outstanding, headroom);
    }
}
