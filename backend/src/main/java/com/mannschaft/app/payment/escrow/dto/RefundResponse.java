package com.mannschaft.app.payment.escrow.dto;

import com.mannschaft.app.payment.escrow.EscrowStatus;

import java.util.UUID;

/**
 * F22.1 謝礼決済 P2-c 第二波: 返金レスポンス（設計書 02 §6.1）。
 *
 * <p>PCI 禁則（{@code client_secret}/{@code pi_xxx}/{@code acct_xxx} 等の機密）は載せない（03 §10）。
 * 返金後の escrow 状態と、額面ベースの返金額・残額のみを返す。capture 前の与信取消では
 * {@code status=CANCELLED}・{@code refundedAmount=0}・{@code residualAmount=0} となる。</p>
 *
 * @param escrowId       エスクロー取引 ID
 * @param status         返金後の escrow 状態（REFUNDED/PARTIALLY_REFUNDED/CANCELLED）
 * @param refundedAmount 今回の返金額（額面ベース・与信取消時は 0）
 * @param residualAmount 残額（face_amount − 既返金累計・額面ベース）
 */
public record RefundResponse(
        UUID escrowId,
        EscrowStatus status,
        long refundedAmount,
        long residualAmount) {
}
