package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * キャンセル履歴サマリーレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CancellationSummaryResponse {

    private final Long teamId;
    private final Long cancelCount;
    private final List<CancellationResponse> cancellations;
}
