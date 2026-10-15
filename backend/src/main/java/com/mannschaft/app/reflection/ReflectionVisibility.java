package com.mannschaft.app.reflection;

/**
 * 振り返りテーマ／エントリの可視性（F06.5・§6.1）。
 *
 * <p>MVP は PRIVATE 固定（{@code reflection_themes.visibility} / {@code reflection_entries.visibility}
 * の CHECK 制約で強制）。FAMILY_SHARED（保護者の学習確認）は別軍議で追加予定（§9.1）。</p>
 */
public enum ReflectionVisibility {
    /** 作成者本人のみ（MVP は PRIVATE 固定・AC-13）。 */
    PRIVATE
}
