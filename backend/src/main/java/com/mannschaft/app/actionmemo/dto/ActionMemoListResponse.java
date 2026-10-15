package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * F02.5 行動メモ一覧レスポンス。
 *
 * <p>次ページ取得用の {@code next_cursor} を含む。最終ページの場合は null。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ActionMemoListResponse {

    private List<ActionMemoResponse> data;

    @JsonProperty("next_cursor")
    private String nextCursor;
}
