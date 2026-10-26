package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F02.5 行動メモタグレスポンス DTO。
 *
 * <p>設計書 §3 の「削除済みタグの API レスポンス表現」に従い、
 * {@code deleted} フラグで論理削除済みかを区別する。</p>
 *
 * <pre>
 * {"id": 12, "name": "運動", "color": "#5DADE2", "sort_order": 0, "deleted": false}
 * {"id": 9,  "name": "夜更かし", "color": null, "sort_order": 1, "deleted": true}
 * </pre>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionMemoTagResponse {

    private Long id;

    private String name;

    private String color;

    @JsonProperty("sort_order")
    private Integer sortOrder;

    @JsonProperty("deleted")
    private boolean deleted;
}
