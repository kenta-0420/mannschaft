package com.mannschaft.app.schedule.entity;

/**
 * スケジュールの作成元を表す enum。
 * <ul>
 *   <li>MANNSCHAFT — Mannschaft 上で直接作成されたスケジュール</li>
 *   <li>GOOGLE_IMPORT — Google カレンダーからインポートされたスケジュール</li>
 * </ul>
 */
public enum ScheduleSource {
    MANNSCHAFT,
    GOOGLE_IMPORT
}
