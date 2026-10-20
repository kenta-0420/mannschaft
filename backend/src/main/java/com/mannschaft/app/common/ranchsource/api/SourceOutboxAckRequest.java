package com.mannschaft.app.common.ranchsource.api;

import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import java.time.Instant;
import java.util.UUID;

/** terminal outcomeだけをACKする。DEFERは別の非消費保留命令へ渡す。 */
public record SourceOutboxAckRequest(UUID eventId,UUID leaseToken,Instant serverTime,
        RanchRewardDeliveryOutcome outcome) {
    public SourceOutboxAckRequest {
        SourceOutboxDeliveryInputs.command(eventId,leaseToken,serverTime);
        if(outcome==null || !outcome.terminal()) throw SourceOutboxDeliveryInputs.invalid();
    }
}
