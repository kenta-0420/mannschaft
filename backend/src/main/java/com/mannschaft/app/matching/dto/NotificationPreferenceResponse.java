package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 通知設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class NotificationPreferenceResponse {

    private final String prefectureCode;
    private final String cityCode;
    private final String activityType;
    private final String category;
    private final Boolean isEnabled;
}
