package com.mannschaft.app.weather.controller;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.weather.config.WeatherRateLimiterConfig;
import com.mannschaft.app.weather.entity.UserWeatherLocationEntity;
import com.mannschaft.app.weather.repository.UserWeatherLocationRepository;
import com.mannschaft.app.weather.service.WeatherForecastService;
import com.mannschaft.app.weather.service.WeatherLocationDeriver;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link WeatherController} の単体テスト（自己スコープ契約テストを兼ねる・認可根治戦役 Wave6 ロットC）。
 *
 * <p>両 EP（getWeatherForecast/refreshWeatherLocation）はいずれも認証主体の {@code USER_ID} のみを
 * {@code UserWeatherLocationRepository} / {@code WeatherLocationDeriver} の検索・更新キーとして使い、
 * 他ユーザーの地点情報には到達できないことを固定する。
 * {@code WeatherController#getWeatherForecast} / {@code WeatherController#refreshWeatherLocation}
 * の自己スコープ性を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WeatherController 単体テスト")
class WeatherControllerTest {

    private static final Long USER_ID = 1L;

    @Mock
    private WeatherForecastService weatherForecastService;

    @Mock
    private WeatherLocationDeriver weatherLocationDeriver;

    @Mock
    private UserWeatherLocationRepository userWeatherLocationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WeatherRateLimiterConfig rateLimiterConfig;

    @InjectMocks
    private WeatherController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("getWeatherForecast: 認証主体自身の userId のみで地点キャッシュを検索する"
            + "（WeatherController#getWeatherForecast）")
    void getWeatherForecast_自己スコープ() throws Exception {
        given(rateLimiterConfig.getForecastBucket(USER_ID)).willReturn(generousBucket());
        given(userWeatherLocationRepository.findByUserIdAndLabel(USER_ID, "home"))
                .willReturn(Optional.empty());
        given(weatherLocationDeriver.deriveAndPersist(USER_ID)).willReturn(Optional.empty());

        assertThat(controller.getWeatherForecast().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        verify(userWeatherLocationRepository, org.mockito.Mockito.atLeastOnce())
                .findByUserIdAndLabel(USER_ID, "home");
        verify(weatherLocationDeriver).deriveAndPersist(USER_ID);
    }

    @Test
    @DisplayName("refreshWeatherLocation: 認証主体自身の userId のみで地点を再導出する"
            + "（WeatherController#refreshWeatherLocation）")
    void refreshWeatherLocation_自己スコープ() {
        given(rateLimiterConfig.getRefreshBucket(USER_ID)).willReturn(generousBucket());
        UserWeatherLocationEntity entity = mock(UserWeatherLocationEntity.class);
        given(entity.getPlaceNameSnapshot()).willReturn("東京都千代田区");
        given(entity.getCountryCode()).willReturn("JP");
        given(entity.getDerivedAt()).willReturn(LocalDateTime.now());
        given(weatherLocationDeriver.deriveAndPersist(USER_ID)).willReturn(Optional.of(entity));

        assertThat(controller.refreshWeatherLocation().getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(weatherLocationDeriver).deriveAndPersist(USER_ID);
    }

    /** テストのみで使う: 十分に大きいレートリミットで実運用 Bucket4j をそのまま利用する裏取り。 */
    private static Bucket generousBucket() {
        return Bucket.builder().addLimit(Bandwidth.simple(1000, Duration.ofHours(1))).build();
    }
}
