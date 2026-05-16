package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 歳時記カレンダーイベント更新リクエスト（F17.1 Phase 2 U4 §2.2）。
 *
 * <p>すべて optional。指定された項目のみ更新する（部分更新）。
 * 終了日のみクリアしたい場合は別途専用 API を設けず、現状は eventEndDate の上書きで対応する。</p>
 *
 * @param title              タイトル（最大 100 文字）
 * @param description        詳細説明
 * @param eventDate          基準日
 * @param eventEndDate       終了日
 * @param isAnnualRecurring  毎年繰返すか
 * @param iconEmoji          表示絵文字（最大 20 文字）
 * @param colorHex           カレンダー表示色 #RRGGBB
 */
public record CalendarEventUpdateRequest(
        @Size(max = 100) String title,
        @Size(max = 5000) String description,
        LocalDate eventDate,
        LocalDate eventEndDate,
        Boolean isAnnualRecurring,
        @Size(max = 20) String iconEmoji,
        @Size(max = 7) String colorHex
) {
}
