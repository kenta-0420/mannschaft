package com.mannschaft.app.ranch.reward;

import com.mannschaft.app.auth.UserOperationErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAckRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxDeferRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxDeliveryFacade;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxFailureRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxLeaseRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxLeasedEvent;
import com.mannschaft.app.ranch.reward.api.RanchRewardConsumer;
import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/** 非 TX の順次窓口。源の lease/ACK、auth lock 下の Ranch 決定は同じ TX に入れない。 */
@Service
@RequiredArgsConstructor
public class RanchRewardDeliveryOrchestrator {
    private static final String TRANSIENT_ERROR = "RANCH_DELIVERY_TRANSIENT";
    private final RanchRewardDeliveryConfigReader config;
    private final RanchRewardDeliveryBounds bounds;
    private final RanchDeliveryControlReader control;
    private final List<SourceOutboxDeliveryFacade> sources;
    private final RanchRewardConsumer consumer;
    private final Clock clock;

    @Transactional(propagation = Propagation.NEVER)
    public RunSummary drainOnce() {
        Instant now = now();
        var selected = config.current(now);
        if (selected.isEmpty()) return RunSummary.EMPTY;
        RanchRewardPolicySnapshot.DeliverySettings settings = selected.orElseThrow();
        bounds.validate(settings);
        if (control.paused()) return RunSummary.EMPTY;

        EnumMap<RanchRewardSourceType, SourceOutboxDeliveryFacade> byType = new EnumMap<>(RanchRewardSourceType.class);
        for (SourceOutboxDeliveryFacade source : sources) {
            if (source == null || source.sourceType() == null
                    || byType.putIfAbsent(source.sourceType(), source) != null) {
                throw new IllegalStateException("報酬源の配送窓口が重複または不正です");
            }
        }
        if (byType.size() != RanchRewardSourceType.values().length) {
            throw new IllegalStateException("四源の配送窓口が揃っていません");
        }

        long leased = 0;
        long acknowledged = 0;
        long deferred = 0;
        long retried = 0;
        long failed = 0;
        for (RanchRewardSourceType type : RanchRewardSourceType.values()) {
            SourceOutboxDeliveryFacade source = byType.get(type);
            List<SourceOutboxLeasedEvent> events;
            try {
                events = Objects.requireNonNull(source.lease(new SourceOutboxLeaseRequest(
                        now(), settings.batchSize(), settings.leaseSeconds(), settings.maxAttempts())));
                if (events.size() > settings.batchSize()) throw new IllegalStateException("源lease件数が上限を超えました");
            } catch (RuntimeException sourceFailure) {
                failed = Math.addExact(failed, 1);
                // 他源へ進む。原因や source の本文はログへ渡さない。
                continue;
            }
            for (SourceOutboxLeasedEvent event : events) {
                if (event == null || event.sourceType() != type) {
                    failed = Math.addExact(failed, 1);
                    continue;
                }
                leased = Math.addExact(leased, 1);
                Instant at = now();
                if (!at.isBefore(event.leaseExpiresAt())) {
                    failed = Math.addExact(failed, 1);
                    continue;
                }
                RanchRewardDeliveryOutcome outcome;
                try {
                    outcome = Objects.requireNonNull(consumer.consume(event.envelope()));
                } catch (RuntimeException transientFailure) {
                    if (transientFailure instanceof BusinessException business
                            && business.getErrorCode() == UserOperationErrorCode.UNAVAILABLE) {
                        try {
                            Instant deferredAt = now();
                            if (source.defer(new SourceOutboxDeferRequest(event.eventId(),
                                    event.leaseToken(), deferredAt,
                                    deferredAt.plusSeconds(settings.initialBackoffSeconds()),
                                    settings.maxBackoffSeconds()))) {
                                deferred = Math.addExact(deferred, 1);
                            } else {
                                failed = Math.addExact(failed, 1);
                            }
                        } catch (RuntimeException ignored) {
                            failed = Math.addExact(failed, 1);
                        }
                        continue;
                    }
                    try {
                        if (source.retry(new SourceOutboxFailureRequest(event.eventId(),
                                event.leaseToken(), now(), settings.maxAttempts(),
                                settings.initialBackoffSeconds(), settings.maxBackoffSeconds(),
                                TRANSIENT_ERROR))) {
                            retried = Math.addExact(retried, 1);
                        } else {
                            failed = Math.addExact(failed, 1);
                        }
                    } catch (RuntimeException ignored) {
                        failed = Math.addExact(failed, 1);
                        // lease 期限または源自身の障害は、次回の源所有 reclaim に任せる。
                    }
                    continue;
                }
                Instant completedAt = now();
                if (outcome.terminal()) {
                    try {
                        if (source.acknowledge(new SourceOutboxAckRequest(event.eventId(),
                                event.leaseToken(), completedAt, outcome))) {
                            acknowledged = Math.addExact(acknowledged, 1);
                        } else {
                            failed = Math.addExact(failed, 1);
                        }
                    } catch (RuntimeException ignored) {
                        failed = Math.addExact(failed, 1);
                        // Ranch 決定は保存済み。源ACKだけを次のleaseで再試行する。
                    }
                } else {
                    try {
                        Instant eligibleAt = completedAt.plusSeconds(settings.initialBackoffSeconds());
                        if (source.defer(new SourceOutboxDeferRequest(event.eventId(),
                                event.leaseToken(), completedAt, eligibleAt,
                                settings.maxBackoffSeconds()))) {
                            deferred = Math.addExact(deferred, 1);
                        } else {
                            failed = Math.addExact(failed, 1);
                        }
                    } catch (RuntimeException ignored) {
                        failed = Math.addExact(failed, 1);
                        // 保留は障害budgetへ移さず、lease失効後の再取得に任せる。
                    }
                }
            }
        }
        return new RunSummary(leased, acknowledged, deferred, retried, failed);
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    public record RunSummary(long leased, long acknowledged, long deferred, long retried, long failed) {
        public static final RunSummary EMPTY = new RunSummary(0, 0, 0, 0, 0);
    }
}
