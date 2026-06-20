package com.mannschaft.app.reflection.dto;

import com.mannschaft.app.reflection.ReflectionLinkedSlotKind;
import com.mannschaft.app.reflection.ReflectionSourceType;
import com.mannschaft.app.reflection.ReflectionVisibility;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * テーマレスポンス（F06.5・§7 #1〜#5）。
 */
@Builder
public record ReflectionThemeResponse(
        String id,
        Long userId,
        String title,
        String description,
        ReflectionSourceType sourceType,
        ReflectionLinkedSlotKind linkedSlotKind,
        Long linkedSlotId,
        LocalDate examDate,
        ReflectionVisibility visibility,
        String recallIntervalDays,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
