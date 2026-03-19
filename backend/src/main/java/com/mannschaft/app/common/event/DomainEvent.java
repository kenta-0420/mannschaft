package com.mannschaft.app.common.event;

import java.time.LocalDateTime;

/**
 * ドメインイベントのマーカーインターフェース。
 * 異なる機能モジュール間の非同期連携に使用する。
 */
public interface DomainEvent {

    /**
     * イベント発生日時を返す。
     */
    LocalDateTime occurredAt();
}
