package com.mannschaft.app.schedule.dto;

/**
 * カレンダー表示色の由来（F03.19 §4.3.2 / R6 裁定）。
 *
 * <p>値域は 4 値で全 API 共通とし、API ごとに {@code USER}/{@code LAYER_USER} のような
 * 別名を作らない（FE が API ごとに分岐する事故を構造的に排除する）。
 * 各値は設計書 §3.4 の色優先順位 1/2/3/4 に一対一で対応する。</p>
 *
 * <p>{@code GET /api/v1/me/calendar-layers} が返しうるのは {@link #LAYER_USER} と
 * {@link #LAYER_AUTO} の 2 値のみ（レイヤーには予定色もカテゴリ色も存在しないため）。
 * 値域が狭いだけで型は共通である。</p>
 */
public enum CalendarColorSource {

    /** 優先1: ユーザーのレイヤー色設定（{@code user_calendar_layer_settings.color}）。 */
    LAYER_USER,

    /** 優先2: 予定自身の色（{@code schedules.color}）。 */
    SCHEDULE,

    /** 優先3: カテゴリ色（{@code schedule_event_categories.color}）。 */
    CATEGORY,

    /** 優先4: 自動色（設計書 §3.3 の決定的ハッシュ）。 */
    LAYER_AUTO
}
