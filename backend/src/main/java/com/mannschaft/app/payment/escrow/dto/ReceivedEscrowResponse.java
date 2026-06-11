package com.mannschaft.app.payment.escrow.dto;

import com.mannschaft.app.payment.escrow.EscrowCaptureMode;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F22.1 謝礼決済 フォロー Wave A: 受取側（payee）の受領エスクロー一覧 1 件分のレスポンス（設計書 02 §1 / 03 §1）。
 *
 * <p>受取側（応じ手＝payee 本人 or そのチーム/組織 ADMIN）が、自分が受け取った謝礼エスクローを一覧し、
 * 返金管理（{@link com.mannschaft.app.payment.escrow.controller.EscrowRefundController}）の対象を選ぶための DTO。
 * 返金は受取側 ADMIN/本人が操作する設計だが、対象 escrow を引き当てる一覧 EP が無かったため新設した（本格的な
 * 返金管理画面用・Wave A）。</p>
 *
 * <p><b>PCI（03 §10）:</b> 本 DTO は受取側向けであり {@code clientSecret}（支払者本人のみが受け取る Stripe.js 用
 * シークレット）を<b>一切含めない</b>。{@code pi_xxx}/{@code acct_xxx} 等の Stripe 生 ID も載せない。状態・金額・
 * 出所・返金累計のみを返し、受取側が「返金可否（CAPTURED/PARTIALLY_REFUNDED か）」と残額を判断できる形にする。</p>
 *
 * <p>金額は最小通貨単位（円整数・long）。{@code refundedAmount} は受取側が実際に受け取った正味（transferAmount＝
 * {@code chargeAmount − applicationFeeAmount}）ベースの返金累計で、FAILED な返金は除く（残額を消費しないため・
 * {@code ConnectChargeService} の返金残額管理と整合）。</p>
 *
 * @param escrowTransactionId  エスクロー取引 ID
 * @param sourceKind           出所種別（RECRUITMENT＝謝礼 / MEMBERSHIP＝会費）
 * @param sourceId             出所 ID（謝礼は札 ID・会費は payment_item_id/team_id 等）
 * @param sourceParticipantId  応募 ID（謝礼のみ・会費は null）
 * @param captureMode          capture モード（MANUAL＝与信後 capture / AUTOMATIC＝即時）
 * @param status               エスクロー状態（返金可否の判断に用いる）
 * @param faceAmount           額面（受取側が設定した謝礼/会費の元値・円整数）
 * @param chargeAmount         課金額（支払者への実請求額＝額面 + 2.5% 上乗せ・円整数）
 * @param applicationFeeAmount Mannschaft 徴収手数料（円整数）
 * @param refundedAmount       返金累計（transferAmount ベース・FAILED 除く・円整数）
 * @param createdAt            起票日時
 */
public record ReceivedEscrowResponse(
        UUID escrowTransactionId,
        EscrowSourceKind sourceKind,
        Long sourceId,
        Long sourceParticipantId,
        EscrowCaptureMode captureMode,
        EscrowStatus status,
        long faceAmount,
        long chargeAmount,
        long applicationFeeAmount,
        long refundedAmount,
        LocalDateTime createdAt) {
}
