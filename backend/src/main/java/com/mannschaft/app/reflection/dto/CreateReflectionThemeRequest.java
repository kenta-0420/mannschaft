package com.mannschaft.app.reflection.dto;

import com.mannschaft.app.reflection.ReflectionLinkedSlotKind;
import com.mannschaft.app.reflection.ReflectionSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * テーマ作成リクエスト（F06.5・§7 #2）。
 *
 * <p>{@code visibility} は MVP 受け付けず PRIVATE 固定、{@code recallIntervalDays} も既定 {@code 1,3,7,14} 固定（§2.6）。</p>
 *
 * @param title              テーマ名（必須）
 * @param description        説明（任意）
 * @param sourceType         SUBJECT/PROJECT/DIARY/FREE（任意・省略時 FREE）
 * @param linkedSlotKind     紐付け時間割スロット種別（任意・TEAM/PERSONAL/null）
 * @param linkedSlotId       紐付け時間割スロットID（任意・他ドメイン論理参照）
 * @param examDate           定期考査日（任意・総まとめリマインド基準）
 * @param linkedSubjectName  Phase 2: 科目名紐づけ（任意・§11.1）
 * @param linkedCourseCode   Phase 2: 履修番号紐づけ（任意・PERSONAL専用・§11.1）
 */
public record CreateReflectionThemeRequest(

        @NotBlank(message = "テーマ名を入力してください")
        @Size(max = 120, message = "テーマ名は120文字以内で入力してください")
        String title,

        @Size(max = 500, message = "説明は500文字以内で入力してください")
        String description,

        ReflectionSourceType sourceType,

        ReflectionLinkedSlotKind linkedSlotKind,

        Long linkedSlotId,

        LocalDate examDate,

        @Size(max = 200, message = "科目名は200文字以内で入力してください")
        String linkedSubjectName,

        @Size(max = 50, message = "履修番号は50文字以内で入力してください")
        String linkedCourseCode
) {
}
