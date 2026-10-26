package com.mannschaft.app.promotion.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 配信対象見積レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AudienceEstimateResponse {

    private final Integer estimatedCount;
}
