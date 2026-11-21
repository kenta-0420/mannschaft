package com.mannschaft.app.signage.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.signage.repository.SignageAccessTokenRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AC3の時刻/IPパーサ境界だけを固定する純試練。
 * Repositoryはprojection入力の供給境界として代替し、実DB/独立TXの資格は実TCP ITへ委ねる。
 */
class SignageSocketAccessReaderTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 12, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    private final SignageAccessTokenRepository tokens = mock(SignageAccessTokenRepository.class);
    private final SignageSocketAccessReader reader = new SignageSocketAccessReader(tokens,
            Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE), new ObjectMapper());

    @Test
    void AC3_無期限と期限前は許可し期限ちょうどと期限後は拒否する() {
        when(tokens.findSocketAccessById(1L, 2L)).thenReturn(Optional.of(new SignageSocketAccess(1L, 2L, (LocalDateTime) null, null)));
        assertThat(reader.permits(1L, 2L, "127.0.0.1")).isTrue();
        when(tokens.findSocketAccessById(1L, 2L)).thenReturn(Optional.of(new SignageSocketAccess(1L, 2L, NOW.plusNanos(1), null)));
        assertThat(reader.permits(1L, 2L, "127.0.0.1")).isTrue();
        for (LocalDateTime expiry : List.of(NOW, NOW.minusNanos(1))) {
            when(tokens.findSocketAccessById(1L, 2L)).thenReturn(Optional.of(new SignageSocketAccess(1L, 2L, expiry, null)));
            assertThat(reader.permits(1L, 2L, "127.0.0.1")).isFalse();
        }
    }

    @Test
    void AC3_同じ瞬間ならClockのゾーンが異なっても期限判定は変わらない() {
        Clock sameInstant = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        for (ZoneId zone : List.of(ZONE, ZoneOffset.UTC, ZoneOffset.ofHours(-8))) {
            SignageSocketAccessReader zonedReader = new SignageSocketAccessReader(tokens,
                    sameInstant.withZone(zone), new ObjectMapper());
            when(tokens.findSocketAccessById(1L, 2L))
                    .thenReturn(Optional.of(new SignageSocketAccess(1L, 2L, NOW.plusNanos(1), null)));
            assertThat(zonedReader.permits(1L, 2L, "127.0.0.1")).as("期限直前: %s", zone).isTrue();
            for (LocalDateTime expiry : List.of(NOW, NOW.minusNanos(1))) {
                when(tokens.findSocketAccessById(1L, 2L))
                        .thenReturn(Optional.of(new SignageSocketAccess(1L, 2L, expiry, null)));
                assertThat(zonedReader.permits(1L, 2L, "127.0.0.1")).as("期限到達後: %s", zone).isFalse();
            }
        }
    }

    @Test
    void AC3_直接peerのIPv4とIPv6とCIDRだけを照合する() {
        for (String json : List.of("[\"127.0.0.1\"]", "[\"127.0.0.0/8\"]")) {
            when(tokens.findSocketAccessById(1L, 2L)).thenReturn(Optional.of(new SignageSocketAccess(1L, 2L, (LocalDateTime) null, json)));
            assertThat(reader.permits(1L, 2L, "127.0.0.1")).isTrue();
            assertThat(reader.permits(1L, 2L, "203.0.113.1")).isFalse();
        }
        when(tokens.findSocketAccessById(1L, 2L)).thenReturn(Optional.of(new SignageSocketAccess(1L, 2L, (LocalDateTime) null, "[\"::1/128\"]")));
        assertThat(reader.permits(1L, 2L, "::1")).isTrue();
        assertThat(reader.permits(1L, 2L, "2001:db8::1")).isFalse();
    }

    @Test
    void AC3_空リストと壊れたIP設定とhostnameはfailClosed() {
        for (String json : List.of("[]", "null", "[null]", "broken", "[\"localhost\"]",
                "[\"127.0.0.1/999\"]", "[\"127.0.0.1\",\"bad\"]")) {
            when(tokens.findSocketAccessById(1L, 2L)).thenReturn(Optional.of(new SignageSocketAccess(1L, 2L, (LocalDateTime) null, json)));
            assertThat(reader.permits(1L, 2L, "127.0.0.1")).as("不正設定の黙認をしない").isFalse();
        }
    }

    @Test
    void AC1_CONNECTは1projectionで検証し空tokenはDBを引かない() {
        assertThat(reader.authenticate(null, "127.0.0.1")).isEmpty();
        assertThat(reader.authenticate(" ", "127.0.0.1")).isEmpty();
        verifyNoInteractions(tokens);
        String raw = "1c147f22-624b-40d9-975c-6ef215bc22e4";
        when(tokens.findSocketAccessByToken(raw)).thenReturn(Optional.of(new SignageSocketAccess(1L, 2L, (LocalDateTime) null, null)));
        assertThat(reader.authenticate(raw, "127.0.0.1")).contains(new SignageSocketAccess(1L, 2L, (LocalDateTime) null, null));
        verify(tokens).findSocketAccessByToken(raw);
    }
}
