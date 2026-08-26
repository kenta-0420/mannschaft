package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 予約タスクレスポンスDTO（機能55 第三陣）。
 *
 * <p>予定詳細で「予約アンケート / 予約出欠募集をいつ作成予定か」を表示するためのレスポンス。
 * PENDING（作成待ち）/ CREATED（作成済み）/ CANCELLED（取消）/ FAILED（失敗）の全状態を露出し、
 * FE が「○月○日 作成予定」「作成済み」等の表示を出し分けられるようにする。</p>
 */
@Builder
@Getter
public class ScheduledTaskResponse {

    /** 予約タスク id（UUIDv7 を文字列化）。 */
    private final String id;

    /** タスク種別（SURVEY / ATTENDANCE）。 */
    private final String taskType;

    /** materialize 予定時刻（この時刻に実体を生成する）。 */
    private final LocalDateTime scheduledAt;

    /** タスク状態（PENDING / CREATED / CANCELLED / FAILED）。 */
    private final String status;

    /** materialize 後の実体 id（event_survey / schedule_attendance 等）。未生成時は null。 */
    private final Long materializedEntityId;
}
