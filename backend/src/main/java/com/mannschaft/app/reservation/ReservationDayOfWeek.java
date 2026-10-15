package com.mannschaft.app.reservation;

import java.time.LocalDate;

/**
 * 予約ドメインの曜日 enum（F03.4.2 §5.2 / B1 曜日表現の正準化）。
 *
 * <p>正準表現は<b>3文字大文字（{@code MON}..{@code SUN}）</b>で一意固定する。
 * 既存 {@code reservation_business_hours.day_of_week}（VARCHAR(3)・実DDL）と完全同一表現であり、
 * 営業時間突合が文字列一致（{@code name()}）で成立することの根拠である。</p>
 *
 * <p><b>{@code java.time.DayOfWeek} は使わない</b>: {@code DayOfWeek.name()} は
 * {@code MONDAY} フルネームのため business_hours の VARCHAR(3) と表現がズレる。
 * request DTO のフィールド型に本 enum を使うことで、不正値（{@code MONDAY}/小文字/その他）は
 * Jackson の enum デシリアライズ失敗で 400 になる（設計書 §4「Jackson enum 400」の実体）。</p>
 */
public enum ReservationDayOfWeek {
    MON, TUE, WED, THU, FRI, SAT, SUN;

    /**
     * 日付から正準3文字曜日へ変換する（設計書 §5.2 の {@code to3Letter(date)}）。
     *
     * <p>{@code java.time.DayOfWeek.name()} は {@code MONDAY} フルネームのため、
     * 先頭3文字（{@code MON}/{@code TUE}/{@code WED}/{@code THU}/{@code FRI}/{@code SAT}/{@code SUN}）へ
     * 変換してからテンプレート・営業時間と突合する。</p>
     *
     * @param date 対象日付
     * @return 正準3文字曜日
     */
    public static ReservationDayOfWeek from(LocalDate date) {
        return valueOf(date.getDayOfWeek().name().substring(0, 3));
    }
}
