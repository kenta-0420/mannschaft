package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * スケジュールメディア更新リクエスト DTO。
 * 全フィールド任意（null の場合は更新しない）。
 */
@Getter
@NoArgsConstructor
public class ScheduleMediaPatchRequest {

    /** キャプション・説明文（最大 500 文字） */
    @Size(max = 500)
    @JsonProperty("caption")
    private String caption;

    /** 撮影日時 */
    @JsonProperty("takenAt")
    private LocalDateTime takenAt;

    /** カバー写真フラグ（ADMIN/DEPUTY_ADMIN のみ変更可） */
    @JsonProperty("isCover")
    private Boolean isCover;

    /** 経費証憑フラグ（MEMBER は true への設定のみ可） */
    @JsonProperty("isExpenseReceipt")
    private Boolean isExpenseReceipt;
}
