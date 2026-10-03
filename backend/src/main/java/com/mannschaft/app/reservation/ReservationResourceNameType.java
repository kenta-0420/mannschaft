package com.mannschaft.app.reservation;

/**
 * チームの予約対象呼称プリセット（F03.4.5 §5 予約対象の呼称チーム設定化）。
 *
 * <p>業種によって「予約対象」の自然な呼び方が異なる（美容室=担当スタッフ、飲食=席、
 * テニスコート=コート 等）ため、チームごとに呼称を選択できるようにする。
 * マスター確定プリセットは {@code STAFF}/{@code SEAT}/{@code COURT}/{@code BED}/{@code LANE}
 * ＋自由入力（{@code CUSTOM}）の 6 種。</p>
 *
 * <p>{@code DEFAULT} はこの 6 種の変更ではなく、未設定チームが従来どおり汎用呼称「予約対象」で
 * 動作し続けるための<b>後方互換フォールバック</b>である（設計判断・F03.4.5 §5.1）。
 * DB カラムの {@code NOT NULL DEFAULT 'DEFAULT'} により、既存行・レコード未作成チームとも
 * 自動的にこの値へ充足される。</p>
 */
public enum ReservationResourceNameType {
    /** 未設定（後方互換フォールバック）。従来どおり「予約対象」という汎用呼称で表示する。 */
    DEFAULT,
    /** 担当スタッフ。 */
    STAFF,
    /** 席。 */
    SEAT,
    /** コート。 */
    COURT,
    /** ベッド。 */
    BED,
    /** レーン。 */
    LANE,
    /** 自由入力。{@link #CUSTOM} のときのみ {@code resourceNameCustom} フィールドを使用する。 */
    CUSTOM
}
