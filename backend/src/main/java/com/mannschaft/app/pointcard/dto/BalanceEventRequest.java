package com.mannschaft.app.pointcard.dto;

import com.mannschaft.app.pointcard.enums.BalanceOperationType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 残高変動イベント記録リクエスト DTO（F18 Phase 3 第二陣 2B）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §12.1 / §6
 *
 * <p>{@code operationType} で CHARGE / SPENT / REFUND を分岐する。
 * いずれの場合も {@code amount} は <strong>正の値</strong>で渡し、SPENT のみ Service 層内部で負に変換する。
 * 0 は {@code POINT_CARD_016 BALANCE_DELTA_ZERO} で拒否する。
 *
 * <p>{@code refundOfEventId} は {@link BalanceOperationType#REFUND} の場合のみ必須。
 * その他の操作種別では無視される。
 *
 * @param operationType    操作種別（CHARGE / SPENT / REFUND）
 * @param amount           金額の絶対値（0.01 〜 1,000,000.00）。0 は不可
 * @param note             任意メモ（最大 200 文字、運営側コメント）
 * @param refundOfEventId  REFUND 時の元 event ID（REFUND 以外は null 可）
 */
public record BalanceEventRequest(
        @NotNull
        BalanceOperationType operationType,

        @NotNull
        @DecimalMin(value = "0.01")
        @DecimalMax(value = "1000000.00")
        BigDecimal amount,

        @Size(max = 200)
        String note,

        UUID refundOfEventId
) {
}
