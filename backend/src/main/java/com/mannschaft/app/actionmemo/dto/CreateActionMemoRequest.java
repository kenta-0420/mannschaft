package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * F02.5 行動メモ作成リクエスト。
 *
 * <p>設計書 §4 に従い、必須項目は content のみ。memo_date 省略時は JST 今日に自動セット。
 * mood は {@code mood_enabled = false} のユーザーが送信しても Service 層で silent に無視される
 * （400 エラーは返さない）。</p>
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
}
