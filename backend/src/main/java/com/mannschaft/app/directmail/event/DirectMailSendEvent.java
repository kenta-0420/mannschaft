package com.mannschaft.app.directmail.event;

import com.mannschaft.app.common.event.DomainEvent;

import java.time.LocalDateTime;

/**
 * ダイレクトメール送信イベント。トランザクションコミット後に非同期でSES送信を実行する。
 */
public record DirectMailSendEvent(Long mailLogId, String scopeType, Long scopeId,
                                   LocalDateTime occurredAt) implements DomainEvent {

    public DirectMailSendEvent(Long mailLogId, String scopeType, Long scopeId) {
        this(mailLogId, scopeType, scopeId, LocalDateTime.now());
    }
}
