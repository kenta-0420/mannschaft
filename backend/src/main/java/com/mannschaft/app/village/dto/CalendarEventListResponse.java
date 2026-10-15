package com.mannschaft.app.village.dto;

import java.util.List;

/**
 * 歳時記カレンダー月別一覧レスポンス（F17.1 Phase 2 U4 §2.2）。
 *
 * @param items 当月に該当するイベント一覧（日付昇順）
 * @param year  対象年
 * @param month 対象月（1〜12）
 */
public record CalendarEventListResponse(
        List<CalendarEventResponse> items,
        int year,
        int month
) {
}
