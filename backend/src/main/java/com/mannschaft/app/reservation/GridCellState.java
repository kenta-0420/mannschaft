package com.mannschaft.app.reservation;

/**
 * 空きグリッド（機能C・§4.C）のセル状態。<b>セル＝時間帯単位</b>で導出される。
 *
 * <p>永続的な {@link SlotStatus}（{@code AVAILABLE}/{@code FULL}/{@code CLOSED}）とは別物で、
 * グリッド表示のために実行時に導出する状態。特に {@link #UNAVAILABLE} は予約不可枠（機能B）との
 * overlap 由来で実行時に決まり、DB には永続化しない（§5.B）。</p>
 *
 * <p>state 決定順（§4.C）: 機能B overlap 該当は最優先で {@link #UNAVAILABLE} に上書きし、
 * それ以外は {@code slot_status} を写像する（{@code AVAILABLE}→{@link #AVAILABLE} /
 * {@code FULL}→{@link #BOOKED} / {@code CLOSED}→{@link #CLOSED}）。</p>
 */
public enum GridCellState {

    /** 予約可能（{@code slot_status = AVAILABLE} かつ予約不可枠に非該当）。 */
    AVAILABLE,

    /** 埋まっている（{@code slot_status = FULL}）。予約者 PII は DTO に構造的に含めない。 */
    BOOKED,

    /** クローズ枠（{@code slot_status = CLOSED}・スタッフ操作/営業時間由来の永続ステータス）。 */
    CLOSED,

    /** 予約不可枠（機能B）との overlap に該当（最優先で上書き・実行時導出）。 */
    UNAVAILABLE
}
