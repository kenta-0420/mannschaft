package com.mannschaft.app.common.event;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * DomainEvent の基底実装。全てのドメインイベントはこのクラスを継承する。
 */
@Getter
public abstract class BaseEvent implements DomainEvent {

    private final LocalDateTime occurredAt;

    protected BaseEvent() {
        this.occurredAt = LocalDateTime.now();
    }

    @Override
    public LocalDateTime occurredAt() {
        return this.occurredAt;
    }
}
