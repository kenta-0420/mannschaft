package com.mannschaft.app.gallery.event;

import com.mannschaft.app.common.event.DomainEvent;

import java.time.LocalDateTime;

/**
 * サムネイル一括再生成イベント。トランザクションコミット後に非同期で再生成を実行する。
 */
public record ThumbnailRegenerateEvent(String jobId, Long teamId, Long organizationId,
                                        LocalDateTime occurredAt) implements DomainEvent {

    public ThumbnailRegenerateEvent(String jobId, Long teamId, Long organizationId) {
        this(jobId, teamId, organizationId, LocalDateTime.now());
    }
}
