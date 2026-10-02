package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

/**
 * キープ並び替えリクエストDTO（F03.17 §4.4.1）。
 *
 * <p>{@code orderedIds} は<b>キープ ID（UUIDv7 の canonical 文字列）を並べたい順に並べた配列</b>。
 * リクエストに含まれない同スコープのキープは {@code sort_order} を据え置く（部分並び替えを許す）。</p>
 *
 * <p><b>部分適用しない</b>のが本 API の要点である（§4.4.1）。1件でも不正な ID が混ざれば
 * 何も更新せずにエラーを返す。混入した他スコープの ID は<b>存在を漏らさないため 404</b>に畳む。</p>
 */
@Getter
public class ReorderScheduleKeepsRequest {

    /** 並べたい順のキープ ID 配列（UUIDv7 canonical 文字列）。 */
    private final List<String> orderedIds;

    @JsonCreator
    public ReorderScheduleKeepsRequest(@JsonProperty("orderedIds") List<String> orderedIds) {
        this.orderedIds = orderedIds;
    }
}
