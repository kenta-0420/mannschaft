package com.mannschaft.app.reflection.dto;

import com.mannschaft.app.reflection.ReflectionLinkedSlotKind;
import com.mannschaft.app.reflection.ReflectionSourceType;
import com.mannschaft.app.reflection.ReflectionVisibility;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * テーマレスポンス（F06.5・§7 #1〜#5）。Phase 2 で linkedSubjectName / linkedCourseCode を追加。
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
        /** Phase 2: 科目名紐づけ（§11.1）。null=未紐づけ。 */
        String linkedSubjectName,
        /** Phase 2: 履修番号紐づけ（§11.1）。null=未設定。 */
        String linkedCourseCode,
        LocalDate examDate,
        ReflectionVisibility visibility,
        String recallIntervalDays,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
