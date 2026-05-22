package com.mannschaft.app.common.timezone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TimezoneContextHolder} の単体テスト。
 * ThreadLocal の set / get / clear の基本動作を検証する。
 */
@DisplayName("TimezoneContextHolder 単体テスト")
class TimezoneContextHolderTest {

    @AfterEach
    void tearDown() {
        // テスト間でのThreadLocal汚染を防止
        TimezoneContextHolder.clear();
    }

    @Test
    @DisplayName("セットした ZoneId が get() で返る")
    void set_get_基本動作() {
        // Given
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");

        // When
        TimezoneContextHolder.set(tokyo);

        // Then
        assertThat(TimezoneContextHolder.get()).isEqualTo(tokyo);
    }

    @Test
    @DisplayName("set していない場合は UTC が返る")
    void 未セット時_UTCが返る() {
        // When（何もセットしない）
        ZoneId result = TimezoneContextHolder.get();

        // Then
        assertThat(result).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("clear 後は UTC が返る")
    void clear後_UTCが返る() {
        // Given
        TimezoneContextHolder.set(ZoneId.of("America/New_York"));

        // When
        TimezoneContextHolder.clear();

        // Then
        assertThat(TimezoneContextHolder.get()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("America/New_York をセットして正しく取得できる")
    void アメリカTZ_セットと取得() {
        // Given
        ZoneId newYork = ZoneId.of("America/New_York");

        // When
        TimezoneContextHolder.set(newYork);

        // Then
        assertThat(TimezoneContextHolder.get()).isEqualTo(newYork);
    }

    @Test
    @DisplayName("別の ZoneId に上書きできる")
    void ZoneId_上書き() {
        // Given
        TimezoneContextHolder.set(ZoneId.of("Asia/Tokyo"));

        // When
        TimezoneContextHolder.set(ZoneId.of("Europe/London"));

        // Then
        assertThat(TimezoneContextHolder.get()).isEqualTo(ZoneId.of("Europe/London"));
    }
}
