package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * F02.5 行動メモ 終業時まとめ投稿リクエスト。
 *
 * <p>設計書 §4 に従い、両フィールドとも任意。</p>
 * <ul>
 *   <li>{@code memo_date} 省略時はサーバー側で JST の今日に自動セット</li>
 *   <li>{@code extra_comment} 省略時はまとめ本文末尾にコメントを追記しない</li>
 * </ul>
 *
 * <p>本文の組み立てに関するフラグ（{@code include_tags} / {@code include_mood} 等）は
 * 意図的に持たない。タグは常に本文に含まれ、mood は
 * {@code user_action_memo_settings.mood_enabled = true} のユーザーでのみ自動表示される
 * （設計書 §11 項目 10）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PublishDailyRequest {

    /** 省略時はサーバー側で JST の今日に自動セット */
    @PastOrPresent(message = "未来の日付には書けません")
    @JsonProperty("memo_date")
    private LocalDate memoDate;

    /** まとめ投稿本文末尾に追記される任意のひと言（最大1000文字） */
    @Size(max = 1000, message = "extra_comment は1000文字以内で入力してください")
    @JsonProperty("extra_comment")
    private String extraComment;
}
