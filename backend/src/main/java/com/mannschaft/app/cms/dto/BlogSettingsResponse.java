package com.mannschaft.app.cms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

/**
 * ブログ設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BlogSettingsResponse {

    private final Boolean selfReviewEnabled;
    private final LocalTime selfReviewStart;
    private final LocalTime selfReviewEnd;
}
