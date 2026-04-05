package com.mannschaft.app.moderation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * モデレーション対応テンプレートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ModerationTemplateResponse {

    private final Long id;
    private final String name;
    private final String actionType;
    private final String reason;
    private final String templateText;
    private final String language;
    private final Boolean isDefault;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
