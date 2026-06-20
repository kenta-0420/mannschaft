package com.mannschaft.app.reflection;

/**
 * 振り返りテーマの source_type（F06.5・§1.1）。
 *
 * <p>{@code reflection_themes.source_type} の CHECK 制約値と完全一致させること。</p>
 */
public enum ReflectionSourceType {
    /** 学生の授業（時間割スロット紐付けの主用途）。 */
    SUBJECT,
    /** 社会人の担当業務・案件。 */
    PROJECT,
    /** 日記（誰でも・テーマ1本）。 */
    DIARY,
    /** 自由テーマ。 */
    FREE
}
