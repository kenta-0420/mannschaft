package com.mannschaft.app.timetable.personal;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * F03.15 個人時間割の時限プリセットテンプレート。
 *
 * <p>設計書 §3「personal_timetable_periods」の備考に基づき、5プリセットと CUSTOM を提供する。
 * CUSTOM は何も投入しない（ユーザーが明示的に時限を編集する想定）。</p>
 *
 * <p>各プリセットの時限定義は教育界での標準的な時間割例に基づき、休憩枠（is_break=true）も
 * 適度に配置する。1時間目開始は学校種別ごとに調整。</p>
 */
public enum PersonalPeriodTemplate {

    ELEMENTARY(List.of(
            new Entry(1, "1限", "08:45", "09:30", false),
            new Entry(2, "2限", "09:35", "10:20", false),
            new Entry(3, "業間休み", "10:20", "10:40", true),
            new Entry(4, "3限", "10:40", "11:25", false),
            new Entry(5, "4限", "11:30", "12:15", false),
            new Entry(6, "給食・昼休み", "12:15", "13:15", true),
            new Entry(7, "5限", "13:15", "14:00", false),
            new Entry(8, "6限", "14:05", "14:50", false))),

    JUNIOR_HIGH(List.of(
            new Entry(1, "1限", "08:50", "09:40", false),
            new Entry(2, "2限", "09:50", "10:40", false),
            new Entry(3, "3限", "10:50", "11:40", false),
            new Entry(4, "4限", "11:50", "12:40", false),
            new Entry(5, "昼休み", "12:40", "13:25", true),
            new Entry(6, "5限", "13:25", "14:15", false),
            new Entry(7, "6限", "14:25", "15:15", false))),

    HIGH_SCHOOL(List.of(
            new Entry(1, "1限", "08:40", "09:30", false),
            new Entry(2, "2限", "09:40", "10:30", false),
            new Entry(3, "3限", "10:40", "11:30", false),
            new Entry(4, "4限", "11:40", "12:30", false),
            new Entry(5, "昼休み", "12:30", "13:15", true),
            new Entry(6, "5限", "13:15", "14:05", false),
            new Entry(7, "6限", "14:15", "15:05", false),
            new Entry(8, "7限", "15:15", "16:05", false))),

    UNIV_90MIN(List.of(
            new Entry(1, "1限", "09:00", "10:30", false),
            new Entry(2, "2限", "10:40", "12:10", false),
            new Entry(3, "昼休み", "12:10", "13:00", true),
            new Entry(4, "3限", "13:00", "14:30", false),
            new Entry(5, "4限", "14:40", "16:10", false),
            new Entry(6, "5限", "16:20", "17:50", false))),

    UNIV_100MIN(List.of(
            new Entry(1, "1限", "08:50", "10:30", false),
            new Entry(2, "2限", "10:40", "12:20", false),
            new Entry(3, "昼休み", "12:20", "13:10", true),
            new Entry(4, "3限", "13:10", "14:50", false),
            new Entry(5, "4限", "15:00", "16:40", false),
            new Entry(6, "5限", "16:50", "18:30", false))),

    /** カスタム: 何も投入しない。 */
    CUSTOM(List.of());

    @Getter
    private final List<Entry> entries;

    PersonalPeriodTemplate(List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * 大文字小文字無視で名前一致するテンプレートを返す。一致しない／null は {@link Optional#empty()}。
     */
    public static Optional<PersonalPeriodTemplate> from(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        try {
            return Optional.of(PersonalPeriodTemplate.valueOf(name.trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** 1時限ぶんのテンプレート行。 */
    @Getter
    @RequiredArgsConstructor
    public static class Entry {
        private final int periodNumber;
        private final String label;
        private final String startTime;
        private final String endTime;
        private final boolean isBreak;

        public LocalTime startTimeOf() {
            return LocalTime.parse(startTime);
        }

        public LocalTime endTimeOf() {
            return LocalTime.parse(endTime);
        }
    }
}
