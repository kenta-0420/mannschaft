package com.mannschaft.app.shift.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * シフト公開イベント。PUBLISHED 遷移時にトランザクション内で発行される。
 * TODO: Google Calendar リスナーは将来追加
 * Phase 4-β: ShiftToTaskListener が購読し Todo 自動作成を行う（実装済み）
 */
@Getter
public class ShiftPublishedEvent extends BaseEvent {

    private final Long scheduleId;
    private final Long teamId;
    private final Long triggeredByUserId;

    /**
     * 当該公開の公開時刻（{@code ShiftScheduleEntity.publishedAt}）。
     *
     * <p>fan-out 抜本改修 Wave-1: 通知の冪等キー {@code source_event_uuid} に混ぜて「同一 AFTER_COMMIT の
     * 二重発火（同一 publishedAt）は 1 ジョブに収束・別公開（PUBLISHED→ADJUSTING→再 PUBLISHED で別 publishedAt）は
     * 新ジョブで再通知」を成立させるために運搬する。再公開通知の恒久抑止を防ぐ。</p>
     */
    private final LocalDateTime publishedAt;

    public ShiftPublishedEvent(Long scheduleId, Long teamId, Long triggeredByUserId, LocalDateTime publishedAt) {
        super();
        this.scheduleId = scheduleId;
        this.teamId = teamId;
        this.triggeredByUserId = triggeredByUserId;
        this.publishedAt = publishedAt;
    }
}
