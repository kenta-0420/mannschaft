package com.mannschaft.app.gallery.event;

import com.mannschaft.app.common.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 写真アップロード完了イベント。
 * トランザクションコミット後に非同期でサムネイル生成（WebP）を実行する。
 */
public record PhotoUploadEvent(List<Long> photoIds, LocalDateTime occurredAt) implements DomainEvent {

    public PhotoUploadEvent(List<Long> photoIds) {
        this(photoIds, LocalDateTime.now());
    }
}
