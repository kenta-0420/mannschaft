package com.mannschaft.app.admin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * アクションテンプレートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ActionTemplateResponse {

    private final Long id;
    private final String name;
    private final String actionType;
    private final String reason;
    private final String templateText;
    private final Boolean isDefault;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
