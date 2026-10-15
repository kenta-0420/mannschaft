package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 通知設定更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateNotificationPreferenceRequest {

    private final String prefectureCode;
    private final String cityCode;
    private final String activityType;
    private final String category;
    private final Boolean isEnabled;
}
