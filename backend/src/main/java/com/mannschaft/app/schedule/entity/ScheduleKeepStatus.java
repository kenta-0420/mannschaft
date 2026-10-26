package com.mannschaft.app.schedule.entity;

/**
 * キープ（日付未定の予定）の状態（F03.17 §5）。
 *
 * <p>DB カラム {@code schedule_keeps.status} は VARCHAR(20) であり、
 * 値の妥当性は本 enum（{@code @Enumerated(EnumType.STRING)}）で担保する
 * （設計書 §3.3.1）。</p>
 */
public enum ScheduleKeepStatus {

    /** 日付未定でキープ中（既定）。converted_schedule_id は必ず NULL。 */
    KEPT,

    /** 予定へ変換済み。converted_schedule_id は必ず非 NULL。 */
    SCHEDULED,

    /** アーカイブ（見送り／完了）。由来により converted_schedule_id の NULL / 非 NULL いずれもあり得る。 */
    ARCHIVED
}
