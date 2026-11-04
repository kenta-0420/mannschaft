package com.mannschaft.app.schedule.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * チーム・組織スコープの予定の表示用の値（他ドメインへ Entity を渡さずに予定を返すための値）。
 *
 * <p>個人ダッシュボードの「今後の予定」が予定 Entity から読んでいた項目だけを持つ。
 * 予定の開始・終了は壁時計の値なので、日時方針（{@code docs/architecture/datetime_policy_utc_instant_vs_wallclock.md}）に従い
 * 日付と時刻に分けて持つ。元の値は {@code LocalDateTime.of(date, time)} でそのまま復元できる（精度も落ちない）。</p>
 *
 * @param id             予定 ID
 * @param teamId         チーム ID（組織予定は null）
 * @param organizationId 組織 ID（チーム予定は null）
 * @param title          タイトル
 * @param startDate      開始日
 * @param startTime      開始時刻
 * @param endDate        終了日（終了なしは null）
 * @param endTime        終了時刻（終了なしは null）
 * @param location       場所
 * @param allDay         終日か
 */
public record ScopeScheduleView(
        Long id,
        Long teamId,
        Long organizationId,
        String title,
        LocalDate startDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        String location,
        Boolean allDay) {
}
