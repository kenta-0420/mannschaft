package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StripeAdInvoiceWebhookControllerTest {

    private static final ZoneId WALL_CLOCK_ZONE = ZoneId.of("Asia/Tokyo");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-09T15:30:00Z");

    @Test
    void paidAtがある場合はwallClockのゾーンで業務ローカル時刻へ変換する() {
        StripeAdInvoiceWebhookController controller = controller(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), WALL_CLOCK_ZONE));

        LocalDateTime paidAt = controller.resolvePaidAt(FIXED_INSTANT.getEpochSecond());

        assertThat(paidAt).isEqualTo(LocalDateTime.of(2026, 4, 10, 0, 30));
    }

    @Test
    void paidAtがない場合はwallClockの現在時刻を使う() {
        StripeAdInvoiceWebhookController controller = controller(Clock.fixed(FIXED_INSTANT, WALL_CLOCK_ZONE));

        LocalDateTime paidAt = controller.resolvePaidAt(null);

        assertThat(paidAt).isEqualTo(LocalDateTime.of(2026, 4, 10, 0, 30));
    }

    private StripeAdInvoiceWebhookController controller(Clock wallClock) {
        return new StripeAdInvoiceWebhookController(
                mock(AdInvoiceRepository.class),
                mock(ApplicationEventPublisher.class),
                wallClock);
    }
}
