package com.mannschaft.app.reflection;

/**
 * テーマが紐づく時間割スロットの種別（F06.5・§2.1）。
 *
 * <p>{@code reflection_themes.linked_slot_kind} の CHECK 制約値と完全一致させること
 * （F03.15 {@code TimetableSlotKind} と同じ表現）。NULL は「スロット非紐付け」を表す。</p>
 */
public enum ReflectionLinkedSlotKind {
    /** チーム時間割スロット。 */
    TEAM,
    /** 個人時間割スロット。 */
    PERSONAL
}
