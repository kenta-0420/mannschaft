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

    // ------------------------------------------------------------------
    // AC-6: 解決済み判定の追加。既存 get() の意味は変えない（Issue #2508）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未セット時は isResolved() が false（バッチスレッドは未解決）")
    void 未セット時_未解決() {
        assertThat(TimezoneContextHolder.isResolved()).isFalse();
        // get() の意味は従来どおり UTC のまま（シリアライザの挙動を変えないため）
        assertThat(TimezoneContextHolder.get()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("set() は解決済みの印を付けない（ユーザー由来でない既定値を積む用途）")
    void set_は解決済みにしない() {
        // When
        TimezoneContextHolder.set(ZoneId.of("America/Los_Angeles"));

        // Then: 値は積まれるが「ユーザー由来」とは見なさない
        assertThat(TimezoneContextHolder.get()).isEqualTo(ZoneId.of("America/Los_Angeles"));
        assertThat(TimezoneContextHolder.isResolved()).isFalse();
    }

    @Test
    @DisplayName("setResolved() は値を積みつつ解決済みの印を付ける")
    void setResolved_は解決済みにする() {
        // Given
        ZoneId losAngeles = ZoneId.of("America/Los_Angeles");

        // When
        TimezoneContextHolder.setResolved(losAngeles);

        // Then
        assertThat(TimezoneContextHolder.get()).isEqualTo(losAngeles);
        assertThat(TimezoneContextHolder.isResolved()).isTrue();
    }

    @Test
    @DisplayName("setResolved() の後に set() を呼ぶと解決済みの印は落ちる（印だけ残る取り違えを防ぐ）")
    void setResolved後のset_で印が落ちる() {
        // Given
        TimezoneContextHolder.setResolved(ZoneId.of("America/Los_Angeles"));

        // When
        TimezoneContextHolder.set(ZoneOffset.UTC);

        // Then
        assertThat(TimezoneContextHolder.get()).isEqualTo(ZoneOffset.UTC);
        assertThat(TimezoneContextHolder.isResolved()).isFalse();
    }

    @Test
    @DisplayName("clear() は解決済みの印も消す（スレッドプール汚染防止）")
    void clear_で印も消える() {
        // Given
        TimezoneContextHolder.setResolved(ZoneId.of("America/Los_Angeles"));

        // When
        TimezoneContextHolder.clear();

        // Then
        assertThat(TimezoneContextHolder.isResolved()).isFalse();
        assertThat(TimezoneContextHolder.get()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("解決済みの印は別スレッドへ伝搬しない（InheritableThreadLocal を使わない方針の固定）")
    void 印は子スレッドへ伝搬しない() throws Exception {
        // Given
        TimezoneContextHolder.setResolved(ZoneId.of("America/Los_Angeles"));

        // When: 子スレッドで観測する
        final boolean[] resolvedInChild = new boolean[1];
        final ZoneId[] zoneInChild = new ZoneId[1];
        Thread child = new Thread(() -> {
            resolvedInChild[0] = TimezoneContextHolder.isResolved();
            zoneInChild[0] = TimezoneContextHolder.get();
        });
        child.start();
        child.join();

        // Then: 伝搬しない（Virtual Threads 環境での誤伝搬を防ぐ方針）
        assertThat(resolvedInChild[0]).isFalse();
        assertThat(zoneInChild[0]).isEqualTo(ZoneOffset.UTC);
    }
}
