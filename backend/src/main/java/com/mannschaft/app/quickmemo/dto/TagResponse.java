package com.mannschaft.app.quickmemo.dto;

import com.mannschaft.app.quickmemo.entity.TagEntity;

import java.time.LocalDateTime;

/**
 * タグレスポンス。
 */
public record TagResponse(
        Long id,
        String scopeType,
        Long scopeId,
        String name,
        String color,
        Integer usageCount,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TagResponse from(TagEntity entity) {
        return new TagResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getName(),
                entity.getColor(),
                entity.getUsageCount(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
