package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.actionmemo.enums.OrgVisibility;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * F02.5 行動メモ更新リクエスト（PATCH）。
 *
 * <p>全フィールド任意。送信されたフィールドのみ更新する。
 * content を送る場合は空文字不可・5,000文字以内。</p>
 *
 * <p><b>Phase 3 追加フィールド</b>: category / duration_minutes / progress_rate / completes_todo。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateActionMemoRequest {

    @Size(max = 5000, message = "メモ本文は5,000文字以内で入力してください")
    private String content;

    @PastOrPresent(message = "未来の日付には書けません")
    @JsonProperty("memo_date")
    private LocalDate memoDate;

    private ActionMemoMood mood;

    @JsonProperty("related_todo_id")
    private Long relatedTodoId;

    @JsonProperty("tag_ids")
    private List<Long> tagIds;

    /** Phase 3: カテゴリ（WORK / PRIVATE / OTHER）。省略時は変更しない。 */
    private ActionMemoCategory category;

    /** Phase 3: 実績時間（分）。0〜1440。省略時は変更しない。 */
    @Min(value = 0, message = "実績時間は0以上で入力してください")
    @Max(value = 1440, message = "実績時間は1440分（24時間）以内で入力してください")
    @JsonProperty("duration_minutes")
    private Integer durationMinutes;

    /**
     * Phase 3: 進捗率（0.00〜100.00）。省略時は変更しない。
     * 指定時は relatedTodoId が必須（Service 層でバリデーション）。
     */
    @DecimalMin(value = "0.00", message = "進捗率は0.00以上で入力してください")
    @DecimalMax(value = "100.00", message = "進捗率は100.00以下で入力してください")
    @JsonProperty("progress_rate")
    private BigDecimal progressRate;

    /**
     * Phase 3: このメモ保存時に related_todo_id の TODO を完了状態にするフラグ。
     * null = 変更しない。
     */
    @JsonProperty("completes_todo")
    private Boolean completesTodo;

    /**
     * Phase 4-α: 組織スコープ投稿先組織 ID。null = 変更しない。0 = クリア。
     */
    @JsonProperty("organization_id")
    private Long organizationId;

    /**
     * Phase 4-α: 組織公開範囲。null = 変更しない。
     */
    @JsonProperty("org_visibility")
    private OrgVisibility orgVisibility;
}
