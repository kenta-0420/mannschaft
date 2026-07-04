package com.mannschaft.app.reservation;

/**
 * 予約不可枠（{@code reservation_blocked_times}）の対象軸（機能B・§3.B）。
 *
 * <p>MVP で実際に予約判定へ enforce するのは {@link #TEAM}（全 slot）/ {@link #STAFF}
 * （{@code slot.staff_user_id == resource_id} の slot のみ）の 2 軸のみ。
 * {@link #LINE} / {@link #RESOURCE} は将来 slot にライン軸が入ったときのための
 * enum 拡張点として確保するだけで、MVP では enforce しない（overlap ユーティリティで
 * {@code resourceMatch = false} 扱い）。</p>
 */
public enum ReservationBlockedResourceType {

    /** チーム全体。当日の全 slot を対象にブロックする。 */
    TEAM,

    /** 特定スタッフ。{@code resource_id} と一致する {@code staff_user_id} を持つ slot のみブロックする。 */
    STAFF,

    /** 予約ライン（将来拡張・MVP 未 enforce）。 */
    LINE,

    /** 汎用リソース（将来拡張・MVP 未 enforce）。 */
    RESOURCE
}
