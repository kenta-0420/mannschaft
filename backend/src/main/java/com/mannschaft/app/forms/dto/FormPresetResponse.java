package com.mannschaft.app.forms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * フォームプリセットレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FormPresetResponse {

    private final Long id;
    private final String name;
    private final String description;
    private final String category;
    private final String fieldsJson;
    private final String icon;
    private final String color;
    private final Boolean isActive;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
