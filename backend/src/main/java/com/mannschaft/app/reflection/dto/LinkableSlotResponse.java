package com.mannschaft.app.reflection.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 科目紐づけ候補 1 件（F06.5 Phase 2・§11.3 EP #16）。
 *
 * <p>本人の週全体の時間割スロットを {@code (kind, subjectName, courseCode)} で重複排除した候補。
 * {@code subjectName} が空・NULL のコマは除外済み。</p>
 *
 * @param kind        スロット種別（"PERSONAL" または "TEAM"）
 * @param slotId      代表スロット ID（dedup グループ先頭のスロット ID）
 * @param subjectName 科目名
 * @param courseCode  履修番号（TEAM は常に null）
 * @param teacherName 担当教員名（TEAM は常に null）
 * @param periodLabel 時限ラベル（例: 「1限」）（代表スロットのもの）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LinkableSlotResponse(
        String kind,
        Long slotId,
        String subjectName,
        String courseCode,
        String teacherName,
        String periodLabel
) {
}
