package com.mannschaft.app.common.storage;

import com.mannschaft.app.common.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * S3オブジェクト非同期削除イベント。
 * トランザクションコミット後に非同期でS3オブジェクトを削除する。
 */
public record S3ObjectDeleteEvent(List<String> s3Keys, LocalDateTime occurredAt) implements DomainEvent {

    public S3ObjectDeleteEvent(String s3Key) {
        this(List.of(s3Key), LocalDateTime.now());
    }

    public S3ObjectDeleteEvent(List<String> s3Keys) {
        this(List.copyOf(s3Keys), LocalDateTime.now());
    }
}
