package com.mannschaft.app.billing;

/**
 * F20.1: {@code billing_contracts.status} の状態機械（VARCHAR(12) + CHECK）。
 *
 * <p><b>状態機械（実決済 D-1〜D-4・2026-07-10 御裁可・設計書 01 §4.2 / 02 §決済フロー）:</b></p>
 * <ul>
 *   <li><b>無償フロー（価格 NULL）</b>: 契約作成で即 {@code ACTIVE}＋entitlements 発行。
 *       解約で {@code ACTIVE → CANCELLED}（即時失効）。</li>
 *   <li><b>決済フロー（価格設定済み）</b>:
 *     <ul>
 *       <li>{@code PENDING}: 契約作成＋Checkout 生成済み・入金前（<b>entitlements 未発行</b>）。</li>
 *       <li>{@code PENDING → ACTIVE}: {@code checkout.session.completed} 到達で entitlements 発行。</li>
 *       <li>{@code PENDING → CANCELLED}: {@code checkout.session.expired}・決済 API 失敗の放棄（pointer 解放・再挑戦可）。</li>
 *       <li>{@code ACTIVE → PAST_DUE}: {@code invoice.payment_failed}（current_period_end まで利用可・権利は触らない）。</li>
 *       <li>{@code PAST_DUE → ACTIVE}: {@code invoice.paid} で回復。</li>
 *       <li>{@code ACTIVE →（期末解約予約 cancel_at_period_end）→ EXPIRED}:
 *           解約 API は ACTIVE のまま {@code cancelled_at} をセットし entitlements の valid_until を
 *           current_period_end へ。{@code customer.subscription.deleted} 到達で {@code EXPIRED}＋残 revoke。</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public enum ContractStatus {
    /** 決済フローで Checkout 生成済み・入金前（entitlements 未発行）。 */
    PENDING,
    /** 有効（entitlements 発行済み）。 */
    ACTIVE,
    /** 継続課金の支払失敗（current_period_end まで利用可・権利は維持）。 */
    PAST_DUE,
    /** 解約済み（無償=即時／決済フローの放棄）。 */
    CANCELLED,
    /** 失効（期末解約の完了・不払い確定）。 */
    EXPIRED
}
