package com.mannschaft.app.cms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

/**
 * セルフレビュー設定更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateBlogSettingsRequest {

    private final Boolean selfReviewEnabled;
    private final LocalTime selfReviewStart;
    private final LocalTime selfReviewEnd;
}
