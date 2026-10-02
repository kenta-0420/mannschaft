package com.mannschaft.app.reservation;

/**
 * 予約不可枠（{@code reservation_blocked_times}）の対象軸（機能B・§3.B / F03.4.5 §4.2）。
 *
 * <p>予約判定へ実際に enforce するのは {@link #TEAM}（全 slot）/ {@link #STAFF}
 * （{@code slot.staff_user_id == resource_id} の slot のみ）/ {@link #LINE}
 * （{@code slot.line_id == resource_id} の slot のみ・F03.4.5 W2-2 で enforce 化）の 3 軸。
 * {@link #RESOURCE} のみ enum 拡張点として確保するだけで enforce しない（overlap ユーティリティで
 * {@code resourceMatch = false} 扱い）。</p>
 */
public enum ReservationBlockedResourceType {

    /** チーム全体。当日の全 slot を対象にブロックする。 */
    TEAM,

    /** 特定スタッフ。{@code resource_id} と一致する {@code staff_user_id} を持つ slot のみブロックする。 */
    STAFF,

    /**
     * 予約ライン（F03.4.5 §4.2 W2-2 で enforce 済）。{@code resource_id} と一致する
     * {@code slot.line_id} を持つ slot のみブロックする（共通枠 {@code line_id=null} は非該当）。
     */
    LINE,

    /** 汎用リソース（将来拡張・MVP 未 enforce）。 */
    RESOURCE
}
