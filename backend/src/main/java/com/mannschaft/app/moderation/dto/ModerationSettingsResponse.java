package com.mannschaft.app.moderation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * モデレーション設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ModerationSettingsResponse {

    private final Long id;
    private final String settingKey;
    private final String settingValue;
    private final String description;
    private final Long updatedBy;
    private final LocalDateTime updatedAt;
}
