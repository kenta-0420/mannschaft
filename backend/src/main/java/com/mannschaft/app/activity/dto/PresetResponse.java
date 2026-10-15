package com.mannschaft.app.activity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * プリセットテンプレートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PresetResponse {

    private final Long id;
    private final String category;
    private final String name;
    private final String description;
    private final String icon;
    private final String color;
    private final Boolean isParticipantRequired;
    private final String defaultVisibility;
    private final String fieldsJson;
    private final Boolean isActive;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
