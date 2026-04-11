package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * F02.5 行動メモ更新リクエスト（PATCH）。
 *
 * <p>全フィールド任意。送信されたフィールドのみ更新する。
 * content を送る場合は空文字不可・5,000文字以内。</p>
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
}
