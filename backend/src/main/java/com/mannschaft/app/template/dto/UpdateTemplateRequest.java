package com.mannschaft.app.template.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * テンプレート更新リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class UpdateTemplateRequest {

    private final String name;

    private final String description;

    private final String iconUrl;

    private final String category;

    private final Boolean isActive;

    private final List<Long> moduleIds;
}
