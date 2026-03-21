package com.mannschaft.app.performance.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 並び順一括更新レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SortOrderResponse {

    private final int updatedCount;
}
