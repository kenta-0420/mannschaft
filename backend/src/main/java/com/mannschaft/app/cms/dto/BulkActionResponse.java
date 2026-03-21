package com.mannschaft.app.cms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 一括操作レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkActionResponse {

    private final int processedCount;
    private final List<Long> skippedIds;
    private final String action;
}
