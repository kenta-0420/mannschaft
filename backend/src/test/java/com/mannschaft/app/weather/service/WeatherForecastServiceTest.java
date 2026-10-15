package com.mannschaft.app.weather.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.weather.client.WeatherApiClient;
import com.mannschaft.app.weather.client.WeatherForecastData;
import com.mannschaft.app.weather.config.WeatherApiProperties;
import com.mannschaft.app.weather.exception.WeatherProviderException;
import com.mannschaft.app.weather.metrics.WeatherMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F02.10 Phase 2 — {@link WeatherForecastService} の単体テスト。
 *
 * <p>キャッシュヒット（fresh）/ キャッシュミス / stale 延命の 3 シナリオを検証する。
 * 2026-05-18 に 3 日対応へ拡張: テストデータも {@code days[0..2]} の 3 日分を組み立てる。</p>
 */
@DisplayName("WeatherForecastService 単体テスト")
class WeatherForecastServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private WeatherApiClient client;
    private WeatherApiProperties props;
    private ObjectMapper objectMapper;
    private WeatherMetrics weatherMetrics;
    private WeatherForecastService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOps);

        client = mock(WeatherApiClient.class);
        props = new WeatherApiProperties();
        props.setCacheTtlSeconds(3600L);
        props.setStaleTtlSeconds(21600L);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        weatherMetrics = mock(WeatherMetrics.class);
        service = new WeatherForecastService(redisTemplate, client, props, objectMapper, weatherMetrics);
    }

    @Test
    @DisplayName("キャッシュヒット（fresh）: WeatherAPI.com は呼ばれない")
    void getForecast_returnsCachedFreshDataWithoutCallingApi() throws Exception {
        WeatherForecastData fresh = sampleData(Instant.now().minus(10, ChronoUnit.MINUTES));
        String key = WeatherForecastService.buildCacheKey(
                "JP", new BigDecimal("35.5"), new BigDecimal("139.5"), "ja");
        given(valueOps.get(key)).willReturn(objectMapper.writeValueAsString(fresh));

        WeatherForecastResult result = service.getForecast(
                "JP", new BigDecimal("35.5"), new BigDecimal("139.5"), "ja");

        assertThat(result.stale()).isFalse();
        assertThat(result.data().getDays()).hasSize(3);
        assertThat(result.data().getDays().get(0).getConditionCode())
                .isEqualTo(fresh.getDays().get(0).getConditionCode());
        verify(client, never()).fetchForecast(any(), any(), anyString());
    }

    @Test
    @DisplayName("キャッシュミス: WeatherAPI.com を呼び出し Valkey に保存する")
    void getForecast_callsApiAndStoresOnMiss() {
        String key = WeatherForecastService.buildCacheKey(
                "JP", new BigDecimal("35.5"), new BigDecimal("139.5"), "ja");
        given(valueOps.get(key)).willReturn(null);
        WeatherForecastData fetched = sampleData(Instant.now());
        given(client.fetchForecast(any(BigDecimal.class), any(BigDecimal.class), eq("ja")))
                .willReturn(java.util.Optional.of(fetched));

        WeatherForecastResult result = service.getForecast(
                "JP", new BigDecimal("35.5"), new BigDecimal("139.5"), "ja");

        assertThat(result.stale()).isFalse();
        assertThat(result.data()).isSameAs(fetched);
        assertThat(result.data().getDays()).hasSize(3);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(valueOps, atLeastOnce()).set(
                keyCaptor.capture(), anyString(),
                ttlCaptor.capture(), eq(TimeUnit.SECONDS));
        assertThat(keyCaptor.getValue()).isEqualTo(key);
        assertThat(ttlCaptor.getValue()).isEqualTo(props.getCacheTtlSeconds());
    }

    @Test
    @DisplayName("stale 延命: 既存 1h 超キャッシュ + API 失敗 → stale=true で返却・TTL 6h で再保存")
    void getForecast_returnsStaleWhenApiFails() throws Exception {
        // fetchedAt = 90 分前 → fresh ではない（cacheTtlSeconds=3600s 超過）
        WeatherForecastData stale = sampleData(Instant.now().minus(90, ChronoUnit.MINUTES));
        String key = WeatherForecastService.buildCacheKey(
                "JP", new BigDecimal("35.5"), new BigDecimal("139.5"), "ja");
        given(valueOps.get(key)).willReturn(objectMapper.writeValueAsString(stale));
        given(client.fetchForecast(any(BigDecimal.class), any(BigDecimal.class), anyString()))
                .willThrow(new WeatherProviderException("5xx"));

        WeatherForecastResult result = service.getForecast(
                "JP", new BigDecimal("35.5"), new BigDecimal("139.5"), "ja");

        assertThat(result.stale()).isTrue();
        assertThat(result.data().getDays()).hasSize(3);
        assertThat(result.data().getDays().get(0).getConditionCode())
                .isEqualTo(stale.getDays().get(0).getConditionCode());

        // TTL 6 時間で再保存（延命）
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(valueOps, times(1)).set(
                eq(key), anyString(), ttlCaptor.capture(), eq(TimeUnit.SECONDS));
        assertThat(ttlCaptor.getValue()).isEqualTo(props.getStaleTtlSeconds());
    }

    @Test
    @DisplayName("API 失敗 + キャッシュなし → WeatherProviderException")
    void getForecast_throwsWhenApiFailsAndNoCache() {
        given(valueOps.get(anyString())).willReturn(null);
        given(client.fetchForecast(any(BigDecimal.class), any(BigDecimal.class), anyString()))
                .willThrow(new WeatherProviderException("upstream 5xx"));

        assertThatThrownBy(() -> service.getForecast(
                "JP", new BigDecimal("35.5"), new BigDecimal("139.5"), "ja"))
                .isInstanceOf(WeatherProviderException.class);
    }

    @Test
    @DisplayName("キャッシュキー形式: weather:{country}:{lat}:{lon}:{lang}")
    void buildCacheKey_followsDesignSpec() {
        String key = WeatherForecastService.buildCacheKey(
                "JP", new BigDecimal("35.5"), new BigDecimal("139.5"), "ja");
        assertThat(key).isEqualTo("weather:JP:35.5:139.5:ja");
    }

    /** テスト用 DTO 生成（今日・明日・明後日の 3 日分）。 */
    private static WeatherForecastData sampleData(Instant fetchedAt) {
        WeatherForecastData.DayData today = WeatherForecastData.DayData.builder()
                .date(LocalDate.of(2026, 5, 9))
                .conditionCode(1003)
                .conditionText("曇り時々晴れ")
                .maxTempC(new BigDecimal("22.4"))
                .minTempC(new BigDecimal("14.1"))
                .avgHumidity(58)
                .chanceOfRain(10)
                .build();
        WeatherForecastData.DayData tomorrow = WeatherForecastData.DayData.builder()
                .date(LocalDate.of(2026, 5, 10))
                .conditionCode(1063)
                .conditionText("雨")
                .maxTempC(new BigDecimal("18.0"))
                .minTempC(new BigDecimal("13.5"))
                .avgHumidity(82)
                .chanceOfRain(80)
                .build();
        WeatherForecastData.DayData dayAfterTomorrow = WeatherForecastData.DayData.builder()
                .date(LocalDate.of(2026, 5, 11))
                .conditionCode(1000)
                .conditionText("晴れ")
                .maxTempC(new BigDecimal("24.0"))
                .minTempC(new BigDecimal("15.0"))
                .avgHumidity(50)
                .chanceOfRain(5)
                .build();
        return WeatherForecastData.builder()
                .days(List.of(today, tomorrow, dayAfterTomorrow))
                .fetchedAt(fetchedAt)
                .build();
    }
}
