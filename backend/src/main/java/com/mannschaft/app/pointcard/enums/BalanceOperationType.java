package com.mannschaft.app.pointcard.enums;

/**
 * F18 Phase 3: 残高型カードの操作種別。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §12.1 / §16
 *
 * <ul>
 *   <li>{@link #CHARGE}: 入金（delta は正数）。</li>
 *   <li>{@link #SPENT}: 利用（delta は負数）。</li>
 *   <li>{@link #REFUND}: 返金（delta は正数。{@code refund_of_event_id} で元 event を参照）。</li>
 * </ul>
 *
 * <p>DB の CHECK 制約 {@code chk_pcbe_operation_type} と完全に整合させること。
 */
public enum BalanceOperationType {
    CHARGE,
    SPENT,
    REFUND
}
