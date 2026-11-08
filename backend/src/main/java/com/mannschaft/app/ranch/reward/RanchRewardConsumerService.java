package com.mannschaft.app.ranch.reward;

import com.mannschaft.app.auth.dto.DeliveryUserState;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.ranch.reward.api.RanchRewardConsumer;
import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 源lease取引の外で認証状態をlockし、ACTIVE時だけ独立Ranch writerへ渡す。 */
@Service
@RequiredArgsConstructor
public class RanchRewardConsumerService implements RanchRewardConsumer {
    private final UserRewardDeliveryGuard deliveryGuard;
    private final RanchRewardWriter writer;

    @Override
    public RanchRewardDeliveryOutcome consume(RanchRewardEnvelope envelope) {
        Objects.requireNonNull(envelope);
        return deliveryGuard.withLockedDeliveryUser(envelope.recipientUserId(), state ->
                switch (state.lifecycle()) {
                    case ACTIVE -> writer.decide(envelope);
                    case FROZEN, WITHDRAWAL, INELIGIBLE ->
                            new RanchRewardDeliveryOutcome(RanchRewardDeliveryOutcome.Outcome.DEFER, null, 0);
                    case PURGING, PURGED, ABSENT ->
                            new RanchRewardDeliveryOutcome(RanchRewardDeliveryOutcome.Outcome.ACCOUNT_DELETED, null, 0);
                });
    }
}
