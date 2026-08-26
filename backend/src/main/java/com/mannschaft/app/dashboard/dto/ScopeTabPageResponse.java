package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * F22.1: タグ一覧（1 ページ = 6 件固定）のレスポンス DTO。
 *
 * <p>設計書: docs/features/F22.1_swipe_scope_dashboard/02_api_design.md §3.1</p>
 */
@Builder
public record ScopeTabPageResponse(

        /** 当該ページのタグ一覧（最大 6 件）。 */
        @JsonProperty("items") List<ScopeTabItemResponse> items,

        /** 0 始まりのページ番号。 */
        @JsonProperty("page") int page,

        /** 1 ページの件数（固定 6 件）。 */
        @JsonProperty("page_size") int pageSize,

        /** {@code ceil(total_count / 6)}。 */
        @JsonProperty("total_pages") int totalPages,

        /** フィルタ適用後の所属スコープ総数。 */
        @JsonProperty("total_count") int totalCount,

        /** 次ページが存在するか。 */
        @JsonProperty("has_next") boolean hasNext,

        /** 前ページが存在するか。 */
        @JsonProperty("has_prev") boolean hasPrev
) {
}
