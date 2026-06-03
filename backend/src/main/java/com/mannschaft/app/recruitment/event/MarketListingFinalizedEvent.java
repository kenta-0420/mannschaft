package com.mannschaft.app.recruitment.event;

/**
 * F22.1 市: 札の最終認証が確定（{@code FULL→COMPLETED}）したことを表すドメインイベント（設計書 02 §5.3）。
 *
 * <p>recruitment ドメインが <b>札行 {@code PESSIMISTIC_WRITE} ロック直下・確定トランザクション内</b>で発火し、
 * payment.escrow がこれを<b>同期</b>購読して謝礼の払出（capture+transfer）を起こす。並行 confirm を
 * 札行ロックで直列化したまま capture を呼ぶことで二重払出を防ぐ（02 §5.3）。</p>
 *
 * <p>クロスドメイン FK を作らず ID のみを受け渡す疎結合連携（README §7・CLAUDE.md 原則1）。payment ドメインの
 * 型には依存させない。{@code paymentEnabled=false}（謝礼なし札）なら payment 側で capture を起こさない。</p>
 *
 * <ul>
 *   <li>{@code listingId} — 札 ID（escrow の {@code source_id}）。</li>
 *   <li>{@code paymentEnabled} — 謝礼が有効な札か（false なら払出なし）。</li>
 * </ul>
 */
public record MarketListingFinalizedEvent(
        Long listingId,
        boolean paymentEnabled) {
}
