package com.mannschaft.app.schedule.visibility;

import java.util.UUID;

/**
 * 予定コメントの可視性判定用の軽量射影（F03.16 設計書 §4.5.0）。
 *
 * <p>{@code ContentVisibilityResolver} が判定に必要とする {@code id} / {@code scheduleId} の
 * 2 列だけを 1 SQL で取得するための Spring Data JPA インターフェース射影。本文（{@code body}）は
 * 判定に不要なので載せない。</p>
 *
 * <p>{@code com.mannschaft.app.common.visibility.VisibilityProjection} は実装しない。
 * 同 IF は {@code id()} が {@code Long} であることを前提とした BIGINT 主キー用であり、
 * {@code schedule_comments} は UUIDv7 主キーだからである（{@link ScheduleKeepVisibilityProjection}
 * と同じ事情）。</p>
 *
 * <p>可視性判定そのもの（{@code ScheduleCommentVisibilityResolver.evaluateCustom}）は
 * {@code contentVisibilityChecker.canView(ReferenceType.SCHEDULE, scheduleId, viewerUserId)}
 * 単体に一本化する（独自の閲覧述語は挟まない・§4.5.0）。</p>
 */
public interface ScheduleCommentVisibilityProjection {

    /** コメントの UUIDv7 主キー。 */
    UUID getId();

    /** 親スケジュール ID（{@code schedules.id}・BIGINT）。可視性は親へ完全委譲する。 */
    Long getScheduleId();
}
