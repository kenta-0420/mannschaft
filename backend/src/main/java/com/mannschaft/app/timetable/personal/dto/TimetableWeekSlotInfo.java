package com.mannschaft.app.timetable.personal.dto;

/**
 * F06.5 Phase 2: 週全体コマの軽量情報（§11.3）。
 *
 * <p>{@link com.mannschaft.app.timetable.personal.service.PersonalTimetableDashboardService#listAllWeekSlots}
 * が返すtimetableドメイン内部DTO。reflectionドメインへの依存を避けるため、
 * マッピングは {@code ReflectionLinkableSlotService} 側で行う。</p>
 *
 * @param kind         "PERSONAL" | "TEAM"
 * @param slotId       スロットID（PERSONAL: personal_timetable_slot.id、TEAM: timetable_slot.id）
 * @param subjectName  科目名（nullまたは空の場合はdedup対象外）
 * @param courseCode   コース番号（PERSONALのみ。TEAMはnull）
 * @param teacherName  担当教師名（任意）
 * @param periodLabel  時限ラベル（PERSONALのみ。TEAMはnull）
 */
public record TimetableWeekSlotInfo(
        String kind,
        Long slotId,
        String subjectName,
        String courseCode,
        String teacherName,
        String periodLabel
) {}
