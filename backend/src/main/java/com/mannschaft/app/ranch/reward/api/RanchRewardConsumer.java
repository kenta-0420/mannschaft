package com.mannschaft.app.ranch.reward.api;

/** 源所有transportが短いlease取引を閉じた後に呼ぶRanch内部配送境界。 */
public interface RanchRewardConsumer {
    RanchRewardDeliveryOutcome consume(RanchRewardEnvelope envelope);
}
