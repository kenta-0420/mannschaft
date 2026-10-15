package com.mannschaft.app.knowledgebase.dto;

import com.mannschaft.app.knowledgebase.KbTemplateScopeType;

/**
 * ナレッジベーステンプレートレスポンス。
 */
public record KbTemplateResponse(
        Long id,
        KbTemplateScopeType scopeType,
        Long scopeId,
        String name,
        String body,
        String icon,
        boolean isSystem,
        Long version
) {}
