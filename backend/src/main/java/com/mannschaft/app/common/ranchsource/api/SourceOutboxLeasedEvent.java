package com.mannschaft.app.common.ranchsource.api;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import java.time.Instant;
import java.util.UUID;

/** 内部workerだけの有限lease応答。管理health/HTTP/exportには使用しない。 */
public record SourceOutboxLeasedEvent(RanchRewardSourceType sourceType,UUID eventId,UUID leaseToken,
        Instant leaseExpiresAt,RanchRewardEnvelope envelope,int attemptCount) {
    public SourceOutboxLeasedEvent {
        SourceOutboxDeliveryInputs.time(leaseExpiresAt);
        if(sourceType==null || eventId==null || leaseToken==null || envelope==null || attemptCount<=0
                || sourceType!=envelope.sourceType() || !eventId.equals(envelope.eventId()))
            throw SourceOutboxDeliveryInputs.invalid();
    }
}
