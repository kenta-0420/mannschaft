package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * activity_detail サジェストレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ActivitySuggestionResponse {

    private final String activityDetail;
    private final Long usageCount;
}
