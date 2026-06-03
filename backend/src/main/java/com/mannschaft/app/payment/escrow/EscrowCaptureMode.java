package com.mannschaft.app.payment.escrow;

/**
 * F22.1 統一決済 P2-b: エスクロー取引の capture モード（即時/エスクローの区別）。
 *
 * <p>{@code escrow_transactions.capture_mode}（VARCHAR(10) + CHECK）に対応する。
 * {@code ConnectChargeService}（次波）が Stripe の {@code capture_method} へマッピングする。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/01_data_model.md §3.2 / 02_api_design.md §0。</p>
 */
public enum EscrowCaptureMode {
    /** エスクローモード（謝礼・RECRUITMENT）。与信後に手動 capture（Stripe capture_method=manual）。 */
    MANUAL,
    /** 即時モード（会費・MEMBERSHIP）。与信フェーズなし・即 capture（Stripe capture_method=automatic）。 */
    AUTOMATIC
}
