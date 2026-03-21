package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 並び替え結果レスポンス。
 */
@Getter
@Builder
public class SortOrderResponse {

    private Integer updatedCount;
}
