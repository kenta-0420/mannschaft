package com.mannschaft.app.event.event;

import com.mannschaft.app.common.event.BaseEvent;
import com.mannschaft.app.event.EventScopeType;
import lombok.Getter;

/**
 * イベント作成ドメインイベント。
 *
 * <p>イベントが新規作成されたときに発火し、専用チャットチャンネルの自動生成など
 * クロスドメインの後続処理をトリガーする。</p>
 *
 * <p>クロスドメイン通信はイベント駆動（{@link BaseEvent}）で行い、
 * CLAUDE.md 原則5（@Transactional はドメイン内に閉じる）に準拠する。</p>
 */
@Getter
public class EventCreatedEvent extends BaseEvent {

    /** 作成されたイベントID */
    private final Long eventId;

    /** スコープ種別（TEAM / ORGANIZATION） */
    private final EventScopeType scopeType;

    /** スコープID（チームIDまたは組織ID） */
    private final Long scopeId;

    /** イベントタイトル */
    private final String title;

    public EventCreatedEvent(Long eventId, EventScopeType scopeType, Long scopeId, String title) {
        super();
        this.eventId = eventId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.title = title;
    }
}
