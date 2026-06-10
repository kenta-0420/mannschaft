package com.mannschaft.app.payment.dto;

import java.util.List;

/**
 * F08.9 P2: 後見まとめ払い — 一括チェックアウトレスポンス（設計書 02_api_design §1.2）。
 *
 * <p>{@code POST /api/v1/me/payable-dues/bulk-checkout} の結果。各明細を個別に処理した結果（部分成功）を返す。</p>
 *
 * @param results 各会費項目の処理結果一覧（リクエストの paymentItemIds と同順）
 */
public record BulkCheckoutResponse(List<BulkCheckoutResultItem> results) {
}
