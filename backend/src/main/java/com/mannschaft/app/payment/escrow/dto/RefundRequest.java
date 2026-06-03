package com.mannschaft.app.payment.escrow.dto;

import com.mannschaft.app.payment.escrow.FeeBearer;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * F22.1 謝礼決済 P2-c 第二波: 返金リクエスト（設計書 02 §6.1・feeBearer 2モード）。
 *
 * <p>{@code amount} は<b>受取側が実際に受け取った正味（transferAmount）ベースの精算額</b>
 * （{@code 0 < amount ≤ transferAmount − 既返金累計}）。{@code null} の場合は残額全額（capture 前は与信取消）。
 * 残額管理は両モード共通で transferAmount ベースで行う（webhook 確定ロジックと整合）。</p>
 *
 * <p>{@code feeBearer} は<b>返金時の決済手数料の負担者</b>を受取側 ADMIN が選択する（{@code null}=既定 {@link FeeBearer#PAYER}）。</p>
 * <ul>
 *   <li>{@link FeeBearer#PAYER}（既定・支払者都合）: 支払者へ transferAmount を戻す。受取側±0・Mannschaft±0（1.4% keep）。
 *       Stripe: 明示 {@code TransferReversal} ＋ {@code reverse_transfer=false}/{@code refund_application_fee=false}。</li>
 *   <li>{@link FeeBearer#PAYEE}（受取側の落ち度/中止）: 支払者へ満額 chargeAmount を戻す。Mannschaft は
 *       application_fee も返金（{@code refund_application_fee=true}・1.4% 放棄）。Stripe 決済手数料は標準フローでは
 *       Mannschaft 一時負担（受取側残高からの自動再徴収は Stripe 仕様上不可・§6.1 注記）。
 *       Stripe: {@code reverse_transfer=true}/{@code refund_application_fee=true}。</li>
 * </ul>
 *
 * @param amount       精算額（transferAmount ベース・最小通貨単位・{@code null}=全額）
 * @param feeBearer    手数料負担者（{@code null}=既定 {@link FeeBearer#PAYER}）
 * @param reason       返金理由（{@code requested_by_customer}/{@code duplicate}/{@code cancellation} 等）
 * @param reasonDetail 補足（PII 非含意・任意）
 */
public record RefundRequest(
        @Positive Long amount,
        FeeBearer feeBearer,
        @Size(max = 32) String reason,
        @Size(max = 500) String reasonDetail) {
}
