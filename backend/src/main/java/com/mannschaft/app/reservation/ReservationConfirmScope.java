package com.mannschaft.app.reservation;

/**
 * 予約承認（confirm）の適用範囲（F03.4.5 §6.2 W2-5・MANUAL 承認チームの series まとめ承認）。
 *
 * <p>MANUAL 承認チームで会員が 12 週分の定期予約を入れると PENDING が 12 件並ぶ。
 * 管理者が 1 件ずつ承認する運用は現実的でないため、series 単位の一括承認を用意する（§6.2）。
 * 単票承認（{@link #THIS_ONLY}）は従来どおり可能で、パラメータ省略時の既定でもある。</p>
 */
public enum ReservationConfirmScope {

    /** 指定した予約 1 件のみを承認する（既定・従来挙動）。 */
    THIS_ONLY,

    /**
     * 同一 series の PENDING を一括承認する。
     *
     * <p>対象行は<b>URL の {@code teamId} に属する行のみ</b>に限定される（他チームの行は掴めない）。
     * PENDING でない行はスキップして明細を返す（AC-5-9）。</p>
     */
    SERIES
}
