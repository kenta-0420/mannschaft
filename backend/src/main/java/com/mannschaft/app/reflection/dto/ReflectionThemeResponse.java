package com.mannschaft.app.reflection.dto;

import com.mannschaft.app.reflection.ReflectionLinkedSlotKind;
import com.mannschaft.app.reflection.ReflectionSourceType;
import com.mannschaft.app.reflection.ReflectionVisibility;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * テーマレスポンス（F06.5・§7 #1〜#5）。
 * Phase 2 で linkedSubjectName / linkedCourseCode を追加。
 * Phase 3 で academicYear / termLabel / parentThemeId / archivedAt を追加（§12.5）。
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
        LocalDateTime updatedAt,
        /** Phase 3: 学年度（§12.1）。Integer型（DB は SMALLINT・型統一方針）。null=未設定。 */
        Integer academicYear,
        /** Phase 3: 学期ラベル（§12.1）。null=未設定。 */
        String termLabel,
        /** Phase 3: 親テーマID（UUID文字列・§12.3）。null=トップレベル。 */
        String parentThemeId,
        /** Phase 3: アーカイブ日時（§12.2）。null=アクティブ。 */
        LocalDateTime archivedAt
) {
}
