package com.mannschaft.app.payment.dto;

import java.util.List;

/**
 * F08.9 P2: 後見まとめ払い — 払える未払い会費一覧レスポンス（設計書 02_api_design §1.2）。
 *
 * <p>{@code GET /api/v1/me/payable-dues}。本人＋後見下の子の未払い会費を権原成立分のみまとめて返す。</p>
 *
 * @param items 払える未払い会費明細の一覧
 */
public record PayableDuesResponse(List<PayableDueItem> items) {
}
