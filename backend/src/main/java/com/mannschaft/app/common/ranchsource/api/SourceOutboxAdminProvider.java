package com.mannschaft.app.common.ranchsource.api;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.time.Instant;
import java.util.UUID;

/** 各源が自分の短TXを所有する管理SPI。aggregateにはRepoを渡さない。 */
public interface SourceOutboxAdminProvider {
    RanchRewardSourceType sourceType();
    SourceOutboxHealthRow health(Instant serverTime);
    SourceOutboxAdminRetryAck retry(Long actorUserId,UUID eventId,UUID key,SourceOutboxAdminRetryRequest request,Instant serverTime);
}
