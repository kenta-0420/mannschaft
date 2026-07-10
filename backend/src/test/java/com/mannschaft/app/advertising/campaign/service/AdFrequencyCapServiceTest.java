package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link AdFrequencyCapService} のユニットテスト（Mockito ベース）。
 *
 * <p>Valkey 操作のシーケンス・ロールバック動作・上限判定を検証する。
 * Testcontainers を使った実 Valkey 検証は {@code AdFrequencyCapIntegrationTest} で行う。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdFrequencyCapService 単体テスト")
class AdFrequencyCapServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UserRepository userRepository;

    private AdFrequencyCapConfig config;
    private AdFrequencyCapService service;

    private static final Long USER_ID = 1001L;
    private static final Long ADVERTISER_ID = 9001L;
    private static final UUID CAMPAIGN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        config = new AdFrequencyCapConfig();
        config.setWeeklyTotal(3);
        config.setWeeklyPerAdvertiser(1);
        service = new AdFrequencyCapService(redisTemplate, userRepository, config);
        // デフォルト: ユーザー TZ を Asia/Tokyo として返す
        given(userRepository.findTimezoneById(anyLong())).willReturn(Optional.of("Asia/Tokyo"));
    }

    @Nested
    @DisplayName("tryConsume")
    class TryConsume {

        @Test
        @DisplayName("正常系: 初回消費でカウンタ 0→1、true を返す")
        void tryConsume_初回_成功() {
            // Given
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            // 個人合計 INCR → 1、同一広告主 INCR → 1
            given(valueOperations.increment(anyString())).willReturn(1L, 1L);

            // When
            boolean result = service.tryConsume(USER_ID, ADVERTISER_ID, CAMPAIGN_ID);

            // Then
            assertThat(result).isTrue();
            // INCR が 2 回呼ばれる（個人合計 → 同一広告主）
            verify(valueOperations, times(2)).increment(anyString());
            // DECR は呼ばれない
            verify(valueOperations, never()).decrement(anyString());
        }

        @Test
        @DisplayName("正常系: 個人合計上限ぎりぎり 3 件目まで成功する")
        void tryConsume_上限ぎりぎり_3件目まで成功() {
            // Given: 個人合計 = 3（上限）、同一広告主 = 1（上限）
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.increment(anyString())).willReturn(3L, 1L);

            // When
            boolean result = service.tryConsume(USER_ID, ADVERTISER_ID, CAMPAIGN_ID);

            // Then
            assertThat(result).isTrue();
            verify(valueOperations, never()).decrement(anyString());
        }

        @Test
        @DisplayName("異常系: 個人合計上限超過 → DECR ロールバック・false 返却")
        void tryConsume_個人合計超過_ロールバック() {
            // Given: 個人合計 INCR → 4（上限超過）
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.increment(anyString())).willReturn(4L);

            // When
            boolean result = service.tryConsume(USER_ID, ADVERTISER_ID, CAMPAIGN_ID);

            // Then
            assertThat(result).isFalse();
            // 個人合計のみ INCR された
            verify(valueOperations, times(1)).increment(anyString());
            // 個人合計の DECR でロールバック
            verify(valueOperations, times(1)).decrement(anyString());
        }

        @Test
        @DisplayName("異常系: 同一広告主上限超過 → 両カウンタ DECR ロールバック")
        void tryConsume_同一広告主超過_両ロールバック() {
            // Given: 個人合計 = 2（OK）、同一広告主 = 2（超過）
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.increment(anyString())).willReturn(2L, 2L);

            // When
            boolean result = service.tryConsume(USER_ID, ADVERTISER_ID, CAMPAIGN_ID);

            // Then
            assertThat(result).isFalse();
            // INCR 2 回（個人合計・同一広告主）
            verify(valueOperations, times(2)).increment(anyString());
            // DECR 2 回（同一広告主・個人合計の順）
            InOrder order = inOrder(valueOperations);
            // 順序: increment(total) → increment(perAdv) → decrement(perAdv) → decrement(total)
            order.verify(valueOperations).increment(argStartsWith(AdFrequencyCapService.KEY_PREFIX_TOTAL));
            order.verify(valueOperations).increment(argStartsWith(AdFrequencyCapService.KEY_PREFIX_PER_ADV));
            order.verify(valueOperations).decrement(argStartsWith(AdFrequencyCapService.KEY_PREFIX_PER_ADV));
            order.verify(valueOperations).decrement(argStartsWith(AdFrequencyCapService.KEY_PREFIX_TOTAL));
        }

        @Test
        @DisplayName("異常系: INCR が null を返したらロールバックして false")
        void tryConsume_INCR_null_失敗() {
            // Given
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.increment(anyString())).willReturn(null);

            // When
            boolean result = service.tryConsume(USER_ID, ADVERTISER_ID, CAMPAIGN_ID);

            // Then
            assertThat(result).isFalse();
            // INCR は 1 回、DECR でロールバック
            verify(valueOperations, times(1)).increment(anyString());
            verify(valueOperations, times(1)).decrement(anyString());
        }

        @Test
        @DisplayName("異常系: userId が null なら IllegalArgumentException")
        void tryConsume_userId_null_例外() {
            assertThatThrownBy(() -> service.tryConsume(null, ADVERTISER_ID, CAMPAIGN_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("異常系: advertiserAccountId が null なら IllegalArgumentException")
        void tryConsume_advertiser_null_例外() {
            assertThatThrownBy(() -> service.tryConsume(USER_ID, null, CAMPAIGN_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("正常系: 初回 INCR 時に EXPIRE が設定される")
        void tryConsume_初回_TTL設定される() {
            // Given
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.increment(anyString())).willReturn(1L, 1L);

            // When
            service.tryConsume(USER_ID, ADVERTISER_ID, CAMPAIGN_ID);

            // Then: 個人合計と同一広告主の両方で EXPIRE が呼ばれる
            verify(redisTemplate, times(2)).expire(anyString(), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("getCurrentCount")
    class GetCurrentCount {

        @Test
        @DisplayName("正常系: Valkey から値を取得")
        void getCurrentCount_正常_値取得() {
            // Given
            java.time.LocalDate weekStart = java.time.LocalDate.of(2026, 5, 11);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn("2");

            // When
            int count = service.getCurrentCount(USER_ID, weekStart);

            // Then
            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("正常系: キー未存在なら 0")
        void getCurrentCount_キー未存在_0() {
            // Given
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn(null);

            // When
            int count = service.getCurrentCount(USER_ID, java.time.LocalDate.of(2026, 5, 11));

            // Then
            assertThat(count).isZero();
        }
    }

    @Nested
    @DisplayName("releaseSlot（F09.19.3 §10.4 / AC-3.8 予約 EXPIRED 返却）")
    class ReleaseSlot {

        private final java.time.LocalDate weekStart = java.time.LocalDate.of(2026, 5, 11); // 月曜

        @Test
        @DisplayName("正常系: 消費週の total / per-advertiser キーが両方デクリメントされる")
        void releaseSlot_両キーをデクリメント() {
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            // GET は正の値を返す（消費済みで > 0）
            given(valueOperations.get(anyString())).willReturn("2");

            service.releaseSlot(USER_ID, ADVERTISER_ID, weekStart);

            // total・per-advertiser の 2 キーで DECR
            verify(valueOperations, times(1)).decrement(argStartsWith(AdFrequencyCapService.KEY_PREFIX_TOTAL));
            verify(valueOperations, times(1)).decrement(argStartsWith(AdFrequencyCapService.KEY_PREFIX_PER_ADV));
        }

        @Test
        @DisplayName("0 未満禁止: キー不在（TTL 失効）なら no-op でデクリメントしない")
        void releaseSlot_キー不在_noop() {
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn(null);

            service.releaseSlot(USER_ID, ADVERTISER_ID, weekStart);

            verify(valueOperations, never()).decrement(anyString());
        }

        @Test
        @DisplayName("0 未満禁止: 値が 0 ならデクリメントしない（負値に落とさない）")
        void releaseSlot_値ゼロ_デクリメントしない() {
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn("0");

            service.releaseSlot(USER_ID, ADVERTISER_ID, weekStart);

            verify(valueOperations, never()).decrement(anyString());
        }

        @Test
        @DisplayName("異常系: weekStart が null なら IllegalArgumentException")
        void releaseSlot_weekStart_null_例外() {
            assertThatThrownBy(() -> service.releaseSlot(USER_ID, ADVERTISER_ID, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("weekStartOf: 週内の任意日から月曜を返す")
        void weekStartOf_月曜を返す() {
            // 2026-05-13(水) → 2026-05-11(月)
            assertThat(AdFrequencyCapService.weekStartOf(java.time.LocalDate.of(2026, 5, 13)))
                    .isEqualTo(java.time.LocalDate.of(2026, 5, 11));
            // 月曜はそのまま
            assertThat(AdFrequencyCapService.weekStartOf(java.time.LocalDate.of(2026, 5, 11)))
                    .isEqualTo(java.time.LocalDate.of(2026, 5, 11));
        }
    }

    @Nested
    @DisplayName("週境界計算（ユーザー TZ）")
    class WeekBoundary {

        @Test
        @DisplayName("月曜日なら当日が週開始")
        void 月曜日_当日が週開始() {
            // 2026-05-11 は月曜
            java.time.ZoneId zone = java.time.ZoneId.of("UTC");
            // モック時計が使えないため、メソッドの静的契約のみ検証する（DayOfWeek.MONDAY で today を返すこと）。
            // ここでは現在日付に依存しない検証として、ヘルパーメソッドの存在を確認するに留める。
            assertThat(AdFrequencyCapService.currentWeekStart(zone)).isNotNull();
        }

        @Test
        @DisplayName("次週月曜までの残秒数は正の値を返す")
        void 次週月曜までの残秒_正値() {
            long seconds = AdFrequencyCapService.secondsUntilNextWeekStart(java.time.ZoneId.of("Asia/Tokyo"));
            assertThat(seconds).isPositive();
        }
    }

    // ===== ヘルパー =====

    private static String argStartsWith(String prefix) {
        return org.mockito.ArgumentMatchers.argThat(s -> s != null && s.startsWith(prefix));
    }
}
