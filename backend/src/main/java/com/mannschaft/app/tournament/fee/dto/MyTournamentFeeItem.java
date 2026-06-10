package com.mannschaft.app.tournament.fee.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 認証ユーザーが対象となっている大会参加費の1件分のレスポンス DTO（F08.7.1 Connect 決済）。
 *
 * <p>支払い済みかどうか（{@code alreadyPaid}）を付与し、未払い／済みを一覧画面で
 * フィルタリングできるようにする。金額は面額・払い手サーチャージ・合計の3ビュー。</p>
 */
public record MyTournamentFeeItem(
        UUID feeId,
        Long tournamentId,
        /** 大会名。TournamentRepository が解決できない場合は fee.title で代替。 */
        String tournamentName,
        /** ディビジョン ID（大会全体の参加費の場合は null）。 */
        Long divisionId,
        /** ディビジョン名（現時点では常に null・将来対応）。 */
        String divisionName,
        String title,
        Long paymentItemId,
        /** 面額（payment_item.amount）。 */
        int faceAmount,
        /** 払い手サーチャージ（現時点では 0・将来の PaymentFeeCalculator 連携用）。 */
        int payerSurcharge,
        /** 合計（faceAmount + payerSurcharge）。 */
        int totalCharge,
        /** 支払期限（null = 期限なし）。 */
        LocalDateTime dueDate,
        /** 支払い済みフラグ。対応 payment_item に有効な PAID レコードがあれば true。 */
        boolean alreadyPaid,
        /** 支払い日時（alreadyPaid=false の場合は null）。 */
        LocalDateTime paidAt
) {
}
