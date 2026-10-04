package com.mannschaft.app.common.ranchsource.api;

import java.time.Instant;
import java.util.UUID;

/** 実障害のretryだけが失敗budgetを消費する。例外本文やcauseは渡さない。 */
public record SourceOutboxFailureRequest(UUID eventId,UUID leaseToken,Instant serverTime,
        int maxAttempts,int initialBackoffSeconds,int maxBackoffSeconds,String errorCode) {
    public SourceOutboxFailureRequest {
        SourceOutboxDeliveryInputs.command(eventId,leaseToken,serverTime);
        if(maxAttempts<=0 || initialBackoffSeconds<=0 || maxBackoffSeconds<initialBackoffSeconds
                || errorCode==null || !errorCode.matches("[A-Z][A-Z0-9_]{0,79}"))
            throw SourceOutboxDeliveryInputs.invalid();
        SourceOutboxDeliveryInputs.time(serverTime.plusSeconds(maxBackoffSeconds));
    }
}
