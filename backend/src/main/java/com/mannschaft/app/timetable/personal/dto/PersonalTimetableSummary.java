package com.mannschaft.app.timetable.personal.dto;

/**
 * 個人時間割の学年・学期サマリー（F06.5 Phase 3 term-suggestion 用・§12.1）。
 *
 * <p>reflection ドメインから timetable ドメインへの越境依存を避けるための値オブジェクト。
 * {@code PersonalTimetableService.findEffectiveAt} の返却型として使用する（D-1 ArchUnit 遵守）。</p>
 *
 * @param academicYear 学年度（Integer型・DB は SMALLINT・源泉 PersonalTimetableEntity と型統一）
 * @param termLabel    学期ラベル
 */
public record PersonalTimetableSummary(
        Integer academicYear,
        String termLabel
) {
}
