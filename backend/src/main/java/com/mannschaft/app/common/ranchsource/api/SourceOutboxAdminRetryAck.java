package com.mannschaft.app.common.ranchsource.api;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.time.Instant;
import java.util.UUID;

/** 管理命令の保存ACK。completedAtは再予約命令の時刻で、報酬配送完了とは区別する。 */
public record SourceOutboxAdminRetryAck(UUID commandId, RanchRewardSourceType sourceType,
        UUID eventId, Disposition disposition, Instant completedAt) {
    public enum Disposition { RETRY_SCHEDULED, ALREADY_TERMINAL }
    public SourceOutboxAdminRetryAck {
        if (commandId == null || sourceType == null || eventId == null || disposition == null || completedAt == null) {
            throw new IllegalArgumentException("再処理応答の定義が不正です");
        }
    }
}
