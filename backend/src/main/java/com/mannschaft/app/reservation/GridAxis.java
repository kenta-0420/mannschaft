package com.mannschaft.app.reservation;

/**
 * 空きグリッド応答の列軸（F03.4.4 §4.1）。
 *
 * <ul>
 *   <li>{@link #STAFF} — 既定・従来動作。列＝予約対象スタッフ（＋共通列）。</li>
 *   <li>{@link #LINE} — 列＝予約対象ライン（{@code reservation_lines}）＋共通列。</li>
 * </ul>
 *
 * <p>クエリパラメータ {@code axis} は Service 層で本 enum へ解決する（不正値は 400・
 * 検証位置と文言を Service 層に一元管理する — 設計書 §4.1 パラメータ検証表）。</p>
 */
public enum GridAxis {
    STAFF, LINE
}
