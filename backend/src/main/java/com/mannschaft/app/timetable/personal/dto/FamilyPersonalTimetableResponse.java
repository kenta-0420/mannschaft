package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;

import java.time.LocalDate;

/**
 * F03.15 Phase 5 家族閲覧用 個人時間割メタ情報レスポンス。
 *
 * <p>本人向け {@link PersonalTimetableResponse} と異なり、以下を <strong>意図的に除外</strong>する
 * （設計書 §4 / §6.1 参照）：</p>
 * <ul>
 *   <li>{@code notes}（個人時間割本体の本人向けメモ）</li>
 *   <li>{@code visibility}（共有設定はオーナー本人のみ把握すべき）</li>
 *   <li>{@code created_at} / {@code updated_at}（プライバシー配慮）</li>
 * </ul>
 *
 * <p>表示可: id, name, academic_year, term_label, effective_from/until, status (常に ACTIVE),
 * week_pattern_enabled, week_pattern_base_date。</p>
 */
public record FamilyPersonalTimetableResponse(
        Long id,
        @JsonProperty("user_id") Long userId,
        String name,
        @JsonProperty("academic_year") Integer academicYear,
        @JsonProperty("term_label") String termLabel,
        @JsonProperty("effective_from") LocalDate effectiveFrom,
        @JsonProperty("effective_until") LocalDate effectiveUntil,
        String status,
        @JsonProperty("week_pattern_enabled") Boolean weekPatternEnabled,
        @JsonProperty("week_pattern_base_date") LocalDate weekPatternBaseDate) {

    public static FamilyPersonalTimetableResponse from(PersonalTimetableEntity entity) {
        return new FamilyPersonalTimetableResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getName(),
                entity.getAcademicYear(),
                entity.getTermLabel(),
                entity.getEffectiveFrom(),
                entity.getEffectiveUntil(),
                entity.getStatus().name(),
                entity.getWeekPatternEnabled(),
                entity.getWeekPatternBaseDate());
    }
}
