package com.mannschaft.app.schedule.dto;

import java.time.LocalDateTime;

/**
 * チーム・組織スコープの予定の表示用の値（他ドメインへ Entity を渡さずに予定を返すための値）。
 *
 * <p>個人ダッシュボードの「今後の予定」が予定 Entity から読んでいた項目だけを持つ。</p>
 *
 * @param id             予定 ID
 * @param teamId         チーム ID（組織予定は null）
 * @param organizationId 組織 ID（チーム予定は null）
 * @param title          タイトル
 * @param startAt        開始日時
 * @param endAt          終了日時
 * @param location       場所
 * @param allDay         終日か
 */
public record ScopeScheduleView(
        Long id,
        Long teamId,
        Long organizationId,
        String title,
        LocalDateTime startAt,
        LocalDateTime endAt,
        String location,
        Boolean allDay) {
}
