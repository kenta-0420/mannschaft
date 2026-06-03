package com.mannschaft.app.payment.escrow;

/**
 * F22.1 謝礼決済 P2-c 第二波: 返金時の「決済手数料の負担者」モード（マスター確定・2026-06-03）。
 *
 * <p>受取側 scope の ADMIN が返金時に選択する。額面 X=10,000 / chargeAmount=10,250（face+2.5%）/
 * transferAmount=9,750（face−2.5%）/ application_fee=500 / Stripe 実手数料 ≈369 を例に、各モードの
 * 経済モデルは以下のとおり（設計書 02 §6.1）。</p>
 *
 * <ul>
 *   <li>{@link #PAYER}（支払者負担・支払者都合のキャンセル等）= 既定。decouple 方式
 *       （明示 {@code TransferReversal} ＋ {@code reverse_transfer=false}/{@code refund_application_fee=false}）。
 *       支払者へ {@code transferAmount=9,750} を戻し、受取側±0・Mannschaft±0（1.4% keep）。支払者が
 *       上乗せ手数料＋Stripe 決済手数料を負担する。</li>
 *   <li>{@link #PAYEE}（受取側負担・受取側の落ち度/中止等）。{@code Refund.create(amount=chargeAmount,
 *       reverse_transfer=true, refund_application_fee=true)}。支払者へ満額 {@code chargeAmount=10,250} を戻し、
 *       Mannschaft は application_fee も返金して利益を取らず中立（1.4% 放棄）。
 *       <b>Stripe 決済手数料（≈369）は Stripe 仕様上どの当事者からも自動で再徴収できないため、本モードの
 *       標準 Stripe フローでは Mannschaft が一時的に負担する</b>（受取側残高からの追加徴収は Stripe の
 *       Account Debits=連結口座の同意＋追加コスト＋同一リージョンが必要で、返金 1 件ごとの自動操作には適さない）。
 *       受取側への最終転嫁はリコンシリエーション（§6.3）／次回入金相殺／運用での Account Debits で行う前提とし、
 *       一時負担額は {@code ledger_entries}(FEE) に記録して可視化する（症状を隠さない）。</li>
 * </ul>
 */
public enum FeeBearer {

    /** 支払者負担（既定・支払者都合）。decouple 方式で受取側±0・Mannschaft±0。 */
    PAYER,

    /** 受取側負担（受取側の落ち度/中止）。支払者満額返金＋application_fee 返金。Stripe 手数料は標準フローでは Mannschaft 一時負担。 */
    PAYEE
}
