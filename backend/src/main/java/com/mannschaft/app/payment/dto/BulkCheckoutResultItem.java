package com.mannschaft.app.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * F08.9 P2: 後見まとめ払い — 一括チェックアウト 1 項目の結果（設計書 02_api_design §1.2）。
 *
 * <p>各 paymentItem の起票結果。{@code status="CHECKED_OUT"} は PENDING 起票成功、
 * {@code status="SKIPPED"} は {@code skipReason} の理由でスキップしたことを示す（部分成功モデル）。</p>
 *
 * @param paymentItemId 対象の会費項目 ID
 * @param status        結果（{@code "CHECKED_OUT"} / {@code "SKIPPED"}）
 * @param skipReason    スキップ理由（{@code null} / {@code "ALREADY_PAID"} / {@code "NOT_AUTHORIZED"} /
 *                      {@code "CONNECT_NOT_READY"} / {@code "ITEM_NOT_FOUND"} / {@code "ERROR"}）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BulkCheckoutResultItem(
        Long paymentItemId,
        String status,
        String skipReason
) {

    /** PENDING 起票に成功した結果を作る。 */
    public static BulkCheckoutResultItem checkedOut(Long paymentItemId) {
        return new BulkCheckoutResultItem(paymentItemId, "CHECKED_OUT", null);
    }

    /** 指定理由でスキップした結果を作る。 */
    public static BulkCheckoutResultItem skipped(Long paymentItemId, String skipReason) {
        return new BulkCheckoutResultItem(paymentItemId, "SKIPPED", skipReason);
    }
}
