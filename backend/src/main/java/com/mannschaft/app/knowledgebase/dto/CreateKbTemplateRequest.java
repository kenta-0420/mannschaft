package com.mannschaft.app.knowledgebase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ナレッジベーステンプレート作成リクエスト。
 */
public record CreateKbTemplateRequest(
        @NotBlank @Size(max = 100) String name,
        String body,
        @Size(max = 50) String icon
) {}
