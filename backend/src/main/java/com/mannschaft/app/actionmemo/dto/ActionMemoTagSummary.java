package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F02.5 メモレスポンス内で埋め込まれるタグサマリ。
 *
 * <p>{@code deleted} フラグで論理削除済みかを区別する（設計書 §3 タグ削除仕様）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ActionMemoTagSummary {

    private Long id;

    private String name;

    private String color;

    @JsonProperty("deleted")
    private boolean deleted;
}
