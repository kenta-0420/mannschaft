package com.mannschaft.app.ranch.reward;

/** Ranch永続decisionの七つの終端状態。source ACK専用結果とは分ける。 */
public enum RanchRewardDecisionStatus {
    AWARDED,
    CAPPED,
    SOURCE_COUNT_CAPPED,
    SOURCE_DISABLED,
    NOT_PARTICIPATING,
    REWARDS_PAUSED,
    INELIGIBLE
}
