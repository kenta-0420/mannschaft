package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.actionmemo.enums.OrgVisibility;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
 * F02.5 行動メモ作成リクエスト。
 *
 * <p>設計書 §4 に従い、必須項目は content のみ。memo_date 省略時は JST 今日に自動セット。
 * mood は {@code mood_enabled = false} のユーザーが送信しても Service 層で silent に無視される
 * （400 エラーは返さない）。</p>
 *
 * <p><b>Phase 3 追加フィールド</b>: category / duration_minutes / progress_rate / completes_todo。
 * 全て任意。省略時はデフォルト値 or NULL が適用される。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateActionMemoRequest {

    @NotBlank(message = "メモ本文を入力してください")
    @Size(max = 5000, message = "メモ本文は5,000文字以内で入力してください")
    private String content;

    /** 省略時はサーバー側で JST の今日に自動セット */
    @PastOrPresent(message = "未来の日付には書けません")
    @JsonProperty("memo_date")
    private LocalDate memoDate;

    /** 任意。mood_enabled = false のユーザーは silent に NULL 化される */
    private ActionMemoMood mood;

    @JsonProperty("related_todo_id")
    private Long relatedTodoId;

    @JsonProperty("tag_ids")
    private List<Long> tagIds;

    /**
     * Phase 3: カテゴリ（WORK / PRIVATE / OTHER）。
     * 省略時は設定の defaultCategory（デフォルト: PRIVATE）が適用される。
     */
    private ActionMemoCategory category;

    /**
     * Phase 3: 実績時間（分）。0〜1440。省略時は NULL。
     */
    @Min(value = 0, message = "実績時間は0以上で入力してください")
    @Max(value = 1440, message = "実績時間は1440分（24時間）以内で入力してください")
    @JsonProperty("duration_minutes")
    private Integer durationMinutes;

    /**
     * Phase 3: 進捗率（0.00〜100.00）。省略時は NULL。
     * 指定時は relatedTodoId が必須（Service 層でバリデーション）。
     */
    @DecimalMin(value = "0.00", message = "進捗率は0.00以上で入力してください")
    @DecimalMax(value = "100.00", message = "進捗率は100.00以下で入力してください")
    @JsonProperty("progress_rate")
    private BigDecimal progressRate;

    /**
     * Phase 3: このメモ保存時に related_todo_id の TODO を完了状態にするフラグ。
     * true のとき relatedTodoId が必須（Service 層でバリデーション）。
     */
    @JsonProperty("completes_todo")
    private boolean completesTodo = false;

    /**
     * Phase 4-α: 組織スコープ投稿先組織 ID。省略時は組織スコープなし。
     * 指定時はユーザーがその組織に所属しているかを Service 層で検証する。
     */
    @JsonProperty("organization_id")
    private Long organizationId;

    /**
     * Phase 4-α: 組織公開範囲（TEAM_ONLY / ORG_WIDE）。省略時は TEAM_ONLY 扱い。
     * organizationId が null の場合は無視される。
     */
    @JsonProperty("org_visibility")
    private OrgVisibility orgVisibility;
}
