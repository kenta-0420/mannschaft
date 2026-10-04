package com.mannschaft.app.auth.dto;

import java.util.UUID;

/** 内部配送の現在状態だけを返す。原情報・Entity・日時を公開しない。 */
public record DeliveryUserState(Lifecycle lifecycle, UUID withdrawalAttemptId) {
    public DeliveryUserState { java.util.Objects.requireNonNull(lifecycle); }
    public enum Lifecycle { ACTIVE, FROZEN, WITHDRAWAL, PURGING, PURGED, ABSENT, INELIGIBLE }
}
