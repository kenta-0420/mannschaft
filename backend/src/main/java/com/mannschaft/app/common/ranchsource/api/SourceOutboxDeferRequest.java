package com.mannschaft.app.common.ranchsource.api;

import java.time.Instant;
import java.util.UUID;

/** lifecycle/pausedは失敗上限に混ぜない。policyの有限最大backoff内で次回を指定する。 */
public record SourceOutboxDeferRequest(UUID eventId,UUID leaseToken,Instant serverTime,
        Instant nextEligibleAt,int maxBackoffSeconds) {
    public SourceOutboxDeferRequest {
        SourceOutboxDeliveryInputs.command(eventId,leaseToken,serverTime);
        SourceOutboxDeliveryInputs.time(nextEligibleAt);
        if(maxBackoffSeconds<=0 || !nextEligibleAt.isAfter(serverTime)
                || nextEligibleAt.isAfter(serverTime.plusSeconds(maxBackoffSeconds)))
            throw SourceOutboxDeliveryInputs.invalid();
    }
}
