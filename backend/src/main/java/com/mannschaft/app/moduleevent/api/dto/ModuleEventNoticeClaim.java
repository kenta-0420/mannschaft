package com.mannschaft.app.moduleevent.api.dto;

import lombok.Getter;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * relay が扱う claim 済みの中立 snapshot。Entity・payload・通知内部型を公開しない。
 * 未知版は descriptor を解釈せず empty とし、元の descriptorVersion を保持する。
 * 既知版の破損も empty だが版を偽装しない。adapter が固定 invalid payload へ変換し、
 * 既存 relay の有界失敗経路で扱う。descriptor は現在の配送許可を証明しない。
 */
@Getter
public final class ModuleEventNoticeClaim {
    private final UUID id;
    private final UUID claimToken;
    private final Long noticeRef;
    private final int descriptorVersion;
    private final int attemptCount;
    private final Optional<ModuleEventNoticeDescriptor> descriptor;

    public ModuleEventNoticeClaim(UUID id, UUID claimToken, Long noticeRef, int descriptorVersion,
            int attemptCount, Optional<ModuleEventNoticeDescriptor> descriptor) {
        this.id = Objects.requireNonNull(id, "通知予約 ID は必須");
        this.claimToken = Objects.requireNonNull(claimToken, "claim token は必須");
        this.noticeRef = Objects.requireNonNull(noticeRef, "通知参照 ID は必須");
        this.descriptorVersion = descriptorVersion;
        this.attemptCount = attemptCount;
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor の Optional は必須");
    }
}
