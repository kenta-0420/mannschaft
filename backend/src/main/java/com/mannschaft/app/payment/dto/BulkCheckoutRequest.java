package com.mannschaft.app.payment.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * F08.9 P2: 後見まとめ払い — 一括チェックアウトリクエスト（設計書 02_api_design §1.2）。
 *
 * <p>{@code POST /api/v1/me/payable-dues/bulk-checkout}。単一の受益者（本人または後見下の子）の複数会費項目を
 * まとめて決済起票する。起票直前に再認可・支払い済み判定を行い、権原失効・支払い済みはスキップして部分成功を返す。</p>
 *
 * @param beneficiaryUserId 受益者（会費の対象者）ユーザーID
 * @param paymentItemIds    まとめて決済する会費項目 ID の一覧（1 件以上）
 */
public record BulkCheckoutRequest(
        @NotNull Long beneficiaryUserId,
        @NotEmpty List<Long> paymentItemIds
) {
}
