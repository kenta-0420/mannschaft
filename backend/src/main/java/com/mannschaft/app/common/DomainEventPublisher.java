package com.mannschaft.app.common;

import com.mannschaft.app.common.event.DomainEvent;

/**
 * ドメインイベント発行インターフェース。
 * 実装クラスは Spring の ApplicationEventPublisher を利用してイベントを発行する。
 */
public interface DomainEventPublisher {

    /**
     * ドメインイベントを発行する。
     *
     * @param event 発行するドメインイベント
     */
    void publish(DomainEvent event);
}
