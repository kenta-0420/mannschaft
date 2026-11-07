package com.mannschaft.app.moduleevent.api.event;

import java.util.Objects;

/**
 * M の確定済み運用異常を公開する scalar イベント。ID・業務 payload・元例外を運ばない。
 * M 側は更新確定後かつ TX 外で発行する。ReliableEvent ではなく、同じ永続処理へ再投入しない。
 */
public record ModuleEventOperationalAlertEvent(Kind kind) {
    public ModuleEventOperationalAlertEvent {
        Objects.requireNonNull(kind, "kind");
    }

    public enum Kind {
        QUARANTINED,
        /** 全未完了（IN_PROGRESS 含む）の最古が30分を超過。ちょうど30分は警告しない。 */ OLDEST_PENDING_30_MIN
    }
}
