package com.mannschaft.app.template.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * レベル別利用可否レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class LevelAvailabilityResponse {

    private final String level;
    private final Boolean isAvailable;
    private final String note;
}
