package com.mannschaft.app.event.event;

import com.mannschaft.app.common.event.BaseEvent;
import com.mannschaft.app.event.EventStatus;
import lombok.Getter;

/**
 * イベントステータス変更ドメインイベント。
 *
 * <p>イベントのステータスが変更されたときに発火し、専用チャットチャンネルのアーカイブなど
 * クロスドメインの後続処理をトリガーする。</p>
 *
 * <p>クロスドメイン通信はイベント駆動（{@link BaseEvent}）で行い、
 * CLAUDE.md 原則5（@Transactional はドメイン内に閉じる）に準拠する。</p>
 */
@Getter
public class EventStatusChangedEvent extends BaseEvent {

    /** ステータスが変更されたイベントID */
    private final Long eventId;

    /** 変更後のステータス */
    private final EventStatus newStatus;

    public EventStatusChangedEvent(Long eventId, EventStatus newStatus) {
        super();
        this.eventId = eventId;
        this.newStatus = newStatus;
    }
}
