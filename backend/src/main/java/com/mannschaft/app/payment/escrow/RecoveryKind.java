package com.mannschaft.app.payment.escrow;

/**
 * F22.1 謝礼決済 §6.3: {@link LedgerEntryType#RECOVERY} 仕訳の経路識別（discriminator）。
 *
 * <p>RECOVERY×PAYEE の仕訳は勘定の向き（D/C）だけでは峻別できない 4 経路が同一 escrow 上に同居しうる。
 * 「自己返金時の回収金消失バグ」（§6.3 検分🔴）を根治するため、各 RECOVERY 行に本識別を焼き付け、
 * 「当該 escrow に上乗せ適用した回収の純額」を A 経路（{@link #A_EXECUTION}/{@link #A_RECAPITALIZE}）だけで
 * 導出できるようにする。C1/C2 発生計上（その escrow 自身の手数料）は純額計算から確実に除外される。</p>
 *
 * <p>{@code ledger_entries.recovery_kind}（VARCHAR(16) + CHECK・非 RECOVERY 行は NULL）に対応する。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/01_data_model.md §3.3 / 02_api_design.md §6.3</p>
 */
public enum RecoveryKind {

    /**
     * C1 発生計上: ModeB 返金で Mannschaft が一時負担した「その escrow 自身の実 Stripe 手数料」を
     * 受取側からの未回収残高として計上する（{@code D PLATFORM_FEE = C PAYEE}・{@code stripe_object_id=re_xxx}）。
     */
    C1_ACCRUAL,

    /**
     * C2 補完: C1 が balance_transaction 未確定で先送りした実手数料を、確定後にリコンシリで後追い計上する
     * （C1 と同一会計 {@code D PLATFORM_FEE = C PAYEE}・{@code stripe_object_id=re_xxx}）。
     */
    C2_COMPLETION,

    /**
     * A 回収実行: 他者債務（未回収残高）を当該 charge の application_fee に上乗せして実回収する
     * （{@code D PAYEE = C PLATFORM_FEE}・{@code stripe_object_id=pi_xxx}）。これが「上乗せ適用した回収」の本体。
     */
    A_EXECUTION,

    /**
     * A 再計上: A で回収を上乗せした charge が ModeB 返金/取消で巻き戻った際、回収実行を打ち消す逆仕訳
     * （{@code D PLATFORM_FEE = C PAYEE}・{@code stripe_object_id=re_xxx} または {@code cancel-<id>}）。
     */
    A_RECAPITALIZE
}
