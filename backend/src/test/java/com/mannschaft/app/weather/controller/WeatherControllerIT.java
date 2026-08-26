package com.mannschaft.app.weather.controller;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.weather.client.WeatherForecastData;
import com.mannschaft.app.weather.config.WeatherRateLimiterConfig;
import com.mannschaft.app.weather.entity.UserWeatherLocationEntity;
import com.mannschaft.app.weather.exception.WeatherLocationDeriveException;
import com.mannschaft.app.weather.exception.WeatherProviderException;
import com.mannschaft.app.weather.repository.UserWeatherLocationRepository;
import com.mannschaft.app.weather.service.WeatherForecastResult;
import com.mannschaft.app.weather.service.WeatherForecastService;
import com.mannschaft.app.weather.service.WeatherLocationDeriver;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F02.10 天気ウィジェット — {@link WeatherController} の MockMvc 結合テスト。
 *
 * <p>{@code @WebMvcTest} で Web レイヤーのみを起動し、Service 層は {@link MockitoBean} で差し替える。
 * レートリミット・例外ハンドリング・レスポンス変換の挙動を検証する。</p>
 *
 * <p>認証戦略: {@code @AutoConfigureMockMvc(addFilters = false)} で Spring Security の
 * フィルタチェインを無効化し、{@link SecurityContextHolder} に直接テスト用の認証情報をセットする。</p>
 *
 * <p>Bucket モックの注意: {@code Bucket} を {@code mock()} で作成してから stub し、
 * その後に {@code rateLimiterConfig} の stub に渡す順序を守ること。
 * {@code given(...).willReturn(mock())} の内部評価中に別の stub を走らせると
 * Mockito の {@code UnfinishedStubbingException} が発生する。</p>
 */
@DisplayName("WeatherController 統合テスト")
@WebMvcTest(WeatherController.class)
@AutoConfigureMockMvc(addFilters = false)
class WeatherControllerIT {

    private static final Long USER_ID = 100L;

    @Autowired
    private MockMvc mockMvc;

    // WeatherController の依存
    @MockitoBean
    private WeatherForecastService weatherForecastService;

    @MockitoBean
    private WeatherLocationDeriver weatherLocationDeriver;

    @MockitoBean
    private UserWeatherLocationRepository userWeatherLocationRepository;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private WeatherRateLimiterConfig rateLimiterConfig;

    // JwtAuthenticationFilter の依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // UserLocaleFilter の依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで必要）
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ========================================
    // テストデータ作成ヘルパー
    // ========================================

    /**
     * テスト用の {@link WeatherForecastData} を生成する（今日・明日・明後日の 3 日分）。
     */
    private WeatherForecastData buildForecastData() {
        WeatherForecastData.DayData today = WeatherForecastData.DayData.builder()
                .date(LocalDate.of(2026, 5, 12))
                .conditionCode(1000)
                .conditionText("晴れ")
                .maxTempC(new BigDecimal("25.0"))
                .minTempC(new BigDecimal("18.0"))
                .avgHumidity(60)
                .chanceOfRain(0)
                .build();
        WeatherForecastData.DayData tomorrow = WeatherForecastData.DayData.builder()
                .date(LocalDate.of(2026, 5, 13))
                .conditionCode(1003)
                .conditionText("一部曇り")
                .maxTempC(new BigDecimal("23.0"))
                .minTempC(new BigDecimal("16.0"))
                .avgHumidity(65)
                .chanceOfRain(20)
                .build();
        WeatherForecastData.DayData dayAfterTomorrow = WeatherForecastData.DayData.builder()
                .date(LocalDate.of(2026, 5, 14))
                .conditionCode(1063)
                .conditionText("雨")
                .maxTempC(new BigDecimal("20.0"))
                .minTempC(new BigDecimal("14.0"))
                .avgHumidity(80)
                .chanceOfRain(70)
                .build();
        return WeatherForecastData.builder()
                .days(List.of(today, tomorrow, dayAfterTomorrow))
                .fetchedAt(Instant.parse("2026-05-12T06:00:00Z"))
                .build();
    }

    /**
     * テスト用の {@link UserWeatherLocationEntity} を生成する。
     */
    private UserWeatherLocationEntity buildLocationEntity() {
        return UserWeatherLocationEntity.builder()
                .userId(USER_ID)
                .label("home")
                .countryCode("JP")
                .postalCodeHash("testhash")
                .latitudeRounded(new BigDecimal("35.5"))
                .longitudeRounded(new BigDecimal("139.5"))
                .placeNameSnapshot("東京都千代田区")
                .derivedAt(LocalDateTime.of(2026, 5, 12, 6, 0, 0))
                .build();
    }

    /**
     * レートリミット通過（tryConsume = true）する Bucket を先に stub してから返す。
     *
     * <p>呼び出し元では:
     * <pre>
     *   Bucket bucket = forecastPassBucket();
     *   given(rateLimiterConfig.getForecastBucket(USER_ID)).willReturn(bucket);
     * </pre>
     * と2行に分けて書くこと。
     * {@code given(...).willReturn(forecastPassBucket())} と1行で書くと、
     * Mockito の stub 評価中に内部で別の {@code when()} が走り
     * {@code UnfinishedStubbingException} が発生する。</p>
     */
    private Bucket stubbedBucket(boolean passes) {
        Bucket bucket = mock(Bucket.class);
        // stub は mock() 直後・given() の引数として渡す前に完了させる
        given(bucket.tryConsume(1)).willReturn(passes);
        return bucket;
    }

    // ========================================
    // GET /api/v1/dashboard/weather
    // ========================================

    @Nested
    @DisplayName("GET /api/v1/dashboard/weather")
    class GetWeatherForecast {

        @Test
        @DisplayName("正常系: 既存 Location 有り → 200 返却")
        void 正常系_既存Location有り_200返却() throws Exception {
            UserWeatherLocationEntity loc = buildLocationEntity();
            WeatherForecastData data = buildForecastData();
            WeatherForecastResult result = new WeatherForecastResult(data, false);

            // Bucket の stub は先に完成させてから rateLimiterConfig に渡す
            Bucket bucket = stubbedBucket(true);
            given(rateLimiterConfig.getForecastBucket(USER_ID)).willReturn(bucket);
            given(userWeatherLocationRepository.findByUserIdAndLabel(USER_ID, "home"))
                    .willReturn(Optional.of(loc));
            given(userRepository.findById(USER_ID))
                    .willReturn(Optional.of(UserEntity.builder().locale("ja-JP").build()));
            given(weatherForecastService.getForecast(
                    any(), any(BigDecimal.class), any(BigDecimal.class), any()))
                    .willReturn(result);

            mockMvc.perform(get("/api/v1/dashboard/weather"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.forecasts.length()").value(3))
                    .andExpect(jsonPath("$.data.forecasts[0].conditionCode").value(1000))
                    .andExpect(jsonPath("$.data.forecasts[0].iconKey").value("sunny"))
                    .andExpect(jsonPath("$.data.forecasts[1].conditionCode").value(1003))
                    .andExpect(jsonPath("$.data.forecasts[1].iconKey").value("partly_cloudy"))
                    .andExpect(jsonPath("$.data.forecasts[2].conditionCode").value(1063))
                    .andExpect(jsonPath("$.data.isStale").value(false))
                    .andExpect(jsonPath("$.data.dataSource").value("WeatherAPI.com"));
        }

        @Test
        @DisplayName("正常系: Location 無し → 同期導出して 200 返却")
        void 正常系_Location無し_同期導出して200返却() throws Exception {
            UserWeatherLocationEntity loc = buildLocationEntity();
            WeatherForecastData data = buildForecastData();
            WeatherForecastResult result = new WeatherForecastResult(data, false);

            Bucket bucket = stubbedBucket(true);
            given(rateLimiterConfig.getForecastBucket(USER_ID)).willReturn(bucket);
            // 1回目は空、同期導出後の2回目は存在
            given(userWeatherLocationRepository.findByUserIdAndLabel(USER_ID, "home"))
                    .willReturn(Optional.empty())
                    .willReturn(Optional.of(loc));
            given(weatherLocationDeriver.deriveAndPersist(USER_ID)).willReturn(Optional.of(loc));
            given(userRepository.findById(USER_ID))
                    .willReturn(Optional.of(UserEntity.builder().locale("ja").build()));
            given(weatherForecastService.getForecast(
                    any(), any(BigDecimal.class), any(BigDecimal.class), any()))
                    .willReturn(result);

            mockMvc.perform(get("/api/v1/dashboard/weather"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isStale").value(false));
        }

        @Test
        @DisplayName("正常系: isStale が true のとき 200 に isStale=true")
        void 正常系_isStale_trueのとき200にisStale_true() throws Exception {
            UserWeatherLocationEntity loc = buildLocationEntity();
            WeatherForecastData data = buildForecastData();
            // stale = true
            WeatherForecastResult result = new WeatherForecastResult(data, true);

            Bucket bucket = stubbedBucket(true);
            given(rateLimiterConfig.getForecastBucket(USER_ID)).willReturn(bucket);
            given(userWeatherLocationRepository.findByUserIdAndLabel(USER_ID, "home"))
                    .willReturn(Optional.of(loc));
            given(userRepository.findById(USER_ID))
                    .willReturn(Optional.of(UserEntity.builder().locale("ja").build()));
            given(weatherForecastService.getForecast(
                    any(), any(BigDecimal.class), any(BigDecimal.class), any()))
                    .willReturn(result);

            mockMvc.perform(get("/api/v1/dashboard/weather"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isStale").value(true));
        }

        @Test
        @DisplayName("異常系: POSTAL_CODE_MISSING → 422")
        void 異常系_POSTAL_CODE_MISSING_422() throws Exception {
            Bucket bucket = stubbedBucket(true);
            given(rateLimiterConfig.getForecastBucket(USER_ID)).willReturn(bucket);
            given(userWeatherLocationRepository.findByUserIdAndLabel(USER_ID, "home"))
                    .willReturn(Optional.empty());
            willThrow(new WeatherLocationDeriveException(
                    WeatherLocationDeriveException.ErrorCode.POSTAL_CODE_MISSING))
                    .given(weatherLocationDeriver).deriveAndPersist(USER_ID);

            mockMvc.perform(get("/api/v1/dashboard/weather"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error_code").value("POSTAL_CODE_MISSING"));
        }

        @Test
        @DisplayName("異常系: POSTAL_CODE_NOT_FOUND → 422")
        void 異常系_POSTAL_CODE_NOT_FOUND_422() throws Exception {
            Bucket bucket = stubbedBucket(true);
            given(rateLimiterConfig.getForecastBucket(USER_ID)).willReturn(bucket);
            given(userWeatherLocationRepository.findByUserIdAndLabel(USER_ID, "home"))
                    .willReturn(Optional.empty());
            willThrow(new WeatherLocationDeriveException(
                    WeatherLocationDeriveException.ErrorCode.POSTAL_CODE_NOT_FOUND))
                    .given(weatherLocationDeriver).deriveAndPersist(USER_ID);

            mockMvc.perform(get("/api/v1/dashboard/weather"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error_code").value("POSTAL_CODE_NOT_FOUND"));
        }

        @Test
        @DisplayName("異常系: COUNTRY_NOT_SUPPORTED → 422")
        void 異常系_COUNTRY_NOT_SUPPORTED_422() throws Exception {
            Bucket bucket = stubbedBucket(true);
            given(rateLimiterConfig.getForecastBucket(USER_ID)).willReturn(bucket);
            given(userWeatherLocationRepository.findByUserIdAndLabel(USER_ID, "home"))
                    .willReturn(Optional.empty());
            willThrow(new WeatherLocationDeriveException(
                    WeatherLocationDeriveException.ErrorCode.COUNTRY_NOT_SUPPORTED))
                    .given(weatherLocationDeriver).deriveAndPersist(USER_ID);

            mockMvc.perform(get("/api/v1/dashboard/weather"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error_code").value("COUNTRY_NOT_SUPPORTED"));
        }

        @Test
        @DisplayName("異常系: WeatherProviderException → 503")
        void 異常系_WeatherProviderException_503() throws Exception {
            UserWeatherLocationEntity loc = buildLocationEntity();

            Bucket bucket = stubbedBucket(true);
            given(rateLimiterConfig.getForecastBucket(USER_ID)).willReturn(bucket);
            given(userWeatherLocationRepository.findByUserIdAndLabel(USER_ID, "home"))
                    .willReturn(Optional.of(loc));
            given(userRepository.findById(USER_ID))
                    .willReturn(Optional.of(UserEntity.builder().locale("ja").build()));
            willThrow(new WeatherProviderException("WeatherAPI.com 取得失敗 + stale キャッシュなし"))
                    .given(weatherForecastService).getForecast(
                            any(), any(BigDecimal.class), any(BigDecimal.class), any());

            mockMvc.perform(get("/api/v1/dashboard/weather"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error_code").value("WEATHER_PROVIDER_UNAVAILABLE"));
        }

        @Test
        @DisplayName("レートリミット: バケット枯渇時 → 429 返却")
        void レートリミット_バケット枯渇時_429返却() throws Exception {
            Bucket bucket = stubbedBucket(false);
            given(rateLimiterConfig.getForecastBucket(USER_ID)).willReturn(bucket);

            mockMvc.perform(get("/api/v1/dashboard/weather"))
                    .andExpect(status().isTooManyRequests());
        }
    }

    // ========================================
    // POST /api/v1/users/me/weather-location/refresh
    // ========================================

    @Nested
    @DisplayName("POST /api/v1/users/me/weather-location/refresh")
    class RefreshWeatherLocation {

        @Test
        @DisplayName("正常系: 再導出成功 → 200 返却")
        void 正常系_再導出成功_200返却() throws Exception {
            UserWeatherLocationEntity saved = buildLocationEntity();

            Bucket bucket = stubbedBucket(true);
            given(rateLimiterConfig.getRefreshBucket(USER_ID)).willReturn(bucket);
            given(weatherLocationDeriver.deriveAndPersist(USER_ID)).willReturn(Optional.of(saved));

            mockMvc.perform(post("/api/v1/users/me/weather-location/refresh"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.placeName").value("東京都千代田区"))
                    .andExpect(jsonPath("$.data.countryCode").value("JP"));
        }

        @Test
        @DisplayName("異常系: POSTAL_CODE_NOT_FOUND → 422")
        void 異常系_POSTAL_CODE_NOT_FOUND_422() throws Exception {
            Bucket bucket = stubbedBucket(true);
            given(rateLimiterConfig.getRefreshBucket(USER_ID)).willReturn(bucket);
            willThrow(new WeatherLocationDeriveException(
                    WeatherLocationDeriveException.ErrorCode.POSTAL_CODE_NOT_FOUND))
                    .given(weatherLocationDeriver).deriveAndPersist(USER_ID);

            mockMvc.perform(post("/api/v1/users/me/weather-location/refresh"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error_code").value("POSTAL_CODE_NOT_FOUND"));
        }

        @Test
        @DisplayName("レートリミット: バケット枯渇時 → 429 返却")
        void レートリミット_バケット枯渇時_429返却() throws Exception {
            Bucket bucket = stubbedBucket(false);
            given(rateLimiterConfig.getRefreshBucket(USER_ID)).willReturn(bucket);

            mockMvc.perform(post("/api/v1/users/me/weather-location/refresh"))
                    .andExpect(status().isTooManyRequests());
        }
    }
}
