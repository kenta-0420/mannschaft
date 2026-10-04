package com.mannschaft.app.ranch.reward;

import com.mannschaft.app.common.ranchsource.api.SourceOutboxAckRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxDeferRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxDeliveryFacade;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxFailureRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxLeaseRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxLeasedEvent;
import com.mannschaft.app.ranch.reward.api.RanchRewardConsumer;
import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** source TX を閉じてから Ranch 決定し、終端と保留と障害を別命令へ送る。 */
class RanchRewardDeliveryOrchestratorTest {
    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00.123456Z");
    private static final RanchRewardPolicySnapshot.DeliverySettings SETTINGS =
            new RanchRewardPolicySnapshot.DeliverySettings(2, 30, 3, 5, 20);
    private final RanchRewardDeliveryConfigReader config = mock(RanchRewardDeliveryConfigReader.class);
    private final RanchRewardDeliveryBounds bounds = mock(RanchRewardDeliveryBounds.class);
    private final RanchDeliveryControlReader control = mock(RanchDeliveryControlReader.class);
    private final RanchRewardConsumer consumer = mock(RanchRewardConsumer.class);
    private final List<SourceOutboxDeliveryFacade> sources = sources();
    private final RanchRewardDeliveryOrchestrator orchestrator = new RanchRewardDeliveryOrchestrator(
            config, bounds, control, sources, consumer, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void noPublishedDeliverySettingsNeverLeasesAnySource() {
        when(config.current(NOW)).thenReturn(Optional.empty());
        assertThat(orchestrator.drainOnce()).isEqualTo(RanchRewardDeliveryOrchestrator.RunSummary.EMPTY);
        for (SourceOutboxDeliveryFacade source : sources) verify(source, never()).lease(any());
    }

    @Test
    void committedTerminalDecisionIsAcknowledgedWithItsSavedOutcome() {
        available();
        var event = leased();
        when(sources.get(0).lease(any(SourceOutboxLeaseRequest.class))).thenReturn(List.of(event));
        var saved = new RanchRewardDeliveryOutcome(RanchRewardDeliveryOutcome.Outcome.AWARDED,
                UUID.randomUUID(), 1);
        when(consumer.consume(event.envelope())).thenReturn(saved);
        when(sources.get(0).acknowledge(any(SourceOutboxAckRequest.class))).thenReturn(true);

        assertThat(orchestrator.drainOnce().acknowledged()).isEqualTo(1);
        verify(sources.get(0)).lease(new SourceOutboxLeaseRequest(NOW, 2, 30, 3));
        verify(sources.get(0)).acknowledge(new SourceOutboxAckRequest(
                event.eventId(), event.leaseToken(), NOW, saved));
        verify(sources.get(0), never()).retry(any());
        verify(sources.get(0), never()).defer(any());
    }

    @Test
    void lifecycleDeferUsesNonConsumingCommandAndFailureAloneUsesRetry() {
        available();
        var first = leased();
        var second = leased();
        when(sources.get(0).lease(any(SourceOutboxLeaseRequest.class)))
                .thenReturn(List.of(first, second));
        when(consumer.consume(first.envelope())).thenReturn(new RanchRewardDeliveryOutcome(
                RanchRewardDeliveryOutcome.Outcome.DEFER, null, 0));
        when(consumer.consume(second.envelope())).thenThrow(new IllegalStateException("masked"));
        when(sources.get(0).defer(any(SourceOutboxDeferRequest.class))).thenReturn(true);
        when(sources.get(0).retry(any(SourceOutboxFailureRequest.class))).thenReturn(true);

        var summary = orchestrator.drainOnce();
        assertThat(summary.deferred()).isEqualTo(1);
        assertThat(summary.retried()).isEqualTo(1);
        verify(sources.get(0)).defer(new SourceOutboxDeferRequest(first.eventId(),
                first.leaseToken(), NOW, NOW.plusSeconds(5), 20));
        verify(sources.get(0)).retry(new SourceOutboxFailureRequest(second.eventId(),
                second.leaseToken(), NOW, 3, 5, 20, "RANCH_DELIVERY_TRANSIENT"));
        verify(sources.get(0), never()).acknowledge(any());
    }

    private void available() {
        when(config.current(NOW)).thenReturn(Optional.of(SETTINGS));
        when(control.paused()).thenReturn(false);
    }

    private static List<SourceOutboxDeliveryFacade> sources() {
        List<SourceOutboxDeliveryFacade> result = new ArrayList<>();
        for (RanchRewardSourceType type : RanchRewardSourceType.values()) {
            SourceOutboxDeliveryFacade source = mock(SourceOutboxDeliveryFacade.class);
            when(source.sourceType()).thenReturn(type);
            when(source.lease(any(SourceOutboxLeaseRequest.class))).thenReturn(List.of());
            result.add(source);
        }
        return result;
    }

    private static SourceOutboxLeasedEvent leased() {
        UUID eventId = UUID.randomUUID();
        var envelope = new RanchRewardEnvelope(eventId, 1,
                RanchRewardSourceType.ATTENDANCE_RESPONSE,
                RanchRewardEnvelope.IdType.LONG, "73", RanchRewardEnvelope.ScopeType.PERSONAL,
                null, null, RanchRewardEnvelope.ActorKind.USER, 21L, null, 21L, 21L,
                NOW, RanchRewardEnvelope.Origin.SELF_RESPONSE,
                new RanchRewardEnvelope.Attendance(RanchRewardEnvelope.AttendanceStatus.ATTENDING,
                        false, true));
        return new SourceOutboxLeasedEvent(RanchRewardSourceType.ATTENDANCE_RESPONSE,
                eventId, UUID.randomUUID(), NOW.plusSeconds(30), envelope, 1);
    }
}
