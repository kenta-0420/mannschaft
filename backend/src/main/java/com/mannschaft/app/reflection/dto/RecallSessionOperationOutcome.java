package com.mannschaft.app.reflection.dto;

/** 原文を外へ渡さず、ACTIVE保護中に凍結した配送候補だけを内部で保持する。 */
public record RecallSessionOperationOutcome(RecallSessionResponse response, boolean newCompletion,
        ReflectionRecallRewardPayload rewardCandidate) {
    public RecallSessionOperationOutcome(RecallSessionResponse response,boolean newCompletion) {
        this(response,newCompletion,null);
    }
}
