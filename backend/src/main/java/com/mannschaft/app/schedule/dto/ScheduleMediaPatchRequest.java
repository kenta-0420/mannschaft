package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * スケジュールメディア更新リクエスト DTO（PATCH 用）。
 * F03.12 カレンダー予定メディア管理。
 * すべてのフィールドはオプション（null の場合は更新しない）。
 */
@Getter
@NoArgsConstructor
public class ScheduleMediaPatchRequest {

    /** キャプション（最大500文字） */
    @Size(max = 500)
    private String caption;

    /** 撮影日時 */
    private LocalDateTime takenAt;

    /** カバー写真フラグ（ADMIN/DEPUTY_ADMIN のみ変更可） */
    private Boolean isCover;

    /** 経費領収書フラグ */
    private Boolean isExpenseReceipt;
}
