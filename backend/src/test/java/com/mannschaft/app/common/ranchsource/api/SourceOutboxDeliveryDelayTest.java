package com.mannschaft.app.common.ranchsource.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SourceOutboxDeliveryDelayTest {
    private static final Instant BASE=Instant.parse("2026-10-05T00:00:00Z");

    @ParameterizedTest
    @MethodSource("positiveDurations")
    void positiveCanonicalDurationIsRoundedUp(long seconds,long nanos,int expected) {
        assertThat(SourceOutboxDeliveryDelay.positiveCeilingSeconds(BASE,BASE.plusSeconds(seconds).plusNanos(nanos)))
                .isEqualTo(expected);
    }

    static Stream<Arguments> positiveDurations() {
        return Stream.of(Arguments.of(0L,1_000L,1),Arguments.of(1L,0L,1),
                Arguments.of(1L,1_000L,2),Arguments.of((long)Integer.MAX_VALUE,0L,Integer.MAX_VALUE));
    }

    @ParameterizedTest
    @MethodSource("invalidDurations")
    void invalidDurationIsRejectedWithoutClamping(Instant from,Instant to) {
        assertThatThrownBy(() -> SourceOutboxDeliveryDelay.positiveCeilingSeconds(from,to))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("源配送命令の定義が不正です");
    }

    static Stream<Arguments> invalidDurations() {
        return Stream.of(Arguments.of(BASE,BASE),Arguments.of(BASE,BASE.minusSeconds(1)),
                Arguments.of(BASE,BASE.plusNanos(1)),Arguments.of(null,BASE),Arguments.of(BASE,null),
                Arguments.of(BASE,BASE.plusSeconds(Integer.MAX_VALUE).plusNanos(1_000)),
                Arguments.of(BASE,BASE.plusSeconds((long)Integer.MAX_VALUE+1)));
    }

    @Test
    void deferInputStillEnforcesPublishedMaximumBeforeConversion() {
        assertThatThrownBy(() -> new SourceOutboxDeferRequest(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),
                BASE,BASE.plusSeconds(3).plusNanos(1_000),3)).isInstanceOf(IllegalArgumentException.class);
    }
}
