package com.mannschaft.app.knowledgebase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * ナレッジベーステンプレート更新リクエスト。
 */
public record UpdateKbTemplateRequest(
        @NotBlank @Size(max = 100) String name,
        String body,
        String icon,
        @NotNull Long version
) {}
