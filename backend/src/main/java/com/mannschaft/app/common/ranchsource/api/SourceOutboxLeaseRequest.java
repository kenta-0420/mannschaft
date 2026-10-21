package com.mannschaft.app.common.ranchsource.api;

import java.time.Instant;

/** COREが公開policyから検証済みの値を渡す。源側に既定batch/lease設定を作らない。 */
public record SourceOutboxLeaseRequest(Instant serverTime,int batchSize,int leaseSeconds,int maxAttempts) {
    public SourceOutboxLeaseRequest {
        SourceOutboxDeliveryInputs.time(serverTime);
        if(batchSize<=0 || leaseSeconds<=0 || maxAttempts<=0) throw SourceOutboxDeliveryInputs.invalid();
        SourceOutboxDeliveryInputs.time(serverTime.plusSeconds(leaseSeconds));
    }
}
