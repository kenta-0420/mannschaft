package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.mannschaft.app.shiftbudget.view.BudgetView;
import lombok.Builder;

import java.util.List;

/**
 * F08.7 シフト予算割当 一覧レスポンス DTO。
 *
 * <p>ページング情報を含む。</p>
 *
 * @param items 割当アイテム一覧
 * @param page  現在ページ番号 (0 始まり)
 * @param size  ページサイズ
 * @param total 全件数（参考値, 厳密に必要な場合は count クエリ別途）
 */
@Builder
public record AllocationListResponse(

        @JsonView(BudgetView.Public.class)
        @JsonProperty("items")
        List<AllocationResponse> items,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("page")
        int page,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("size")
        int size,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("total")
        long total
) {
}
