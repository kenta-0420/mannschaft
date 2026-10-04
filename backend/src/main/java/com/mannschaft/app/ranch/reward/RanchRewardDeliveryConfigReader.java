package com.mannschaft.app.ranch.reward;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.ranch.repository.RanchRewardPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** source の lease TX より前に、公開済み配送設定だけを独立 PRIMARY で返す。 */
@Service
@RequiredArgsConstructor
public class RanchRewardDeliveryConfigReader {
    private final RanchRewardPolicyRepository policies;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Optional<RanchRewardPolicySnapshot.DeliverySettings> current(Instant serverTime) {
        Objects.requireNonNull(serverTime);
        return policies.publishedFor(serverTime, PageRequest.of(0, 1)).stream().findFirst()
                .map(row -> RanchRewardPolicyCodec.decode(row.getId(), row.getVersionNumber(),
                        row.getEffectiveAt(), row.getSettingsJson(), row.getContentHash(), json)
                        .delivery());
    }
}
