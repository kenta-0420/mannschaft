package com.mannschaft.app.template.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * テンプレート詳細レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class TemplateResponse {

    private final Long id;
    private final String name;
    private final String slug;
    private final String description;
    private final String iconUrl;
    private final String category;
    private final Boolean isActive;
    private final List<ModuleSummaryResponse> modules;
    private final LocalDateTime createdAt;
}
