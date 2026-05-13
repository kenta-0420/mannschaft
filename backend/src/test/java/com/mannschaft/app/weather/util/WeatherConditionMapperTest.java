package com.mannschaft.app.weather.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WeatherConditionMapper} の単体テスト。
 *
 * <p>static メソッドのみのユーティリティクラスのため、
 * Mockito 拡張は不要。{@code @Test} + {@code assertThat} のみで検証する。</p>
 */
@DisplayName("WeatherConditionMapper 単体テスト")
class WeatherConditionMapperTest {

    @Test
    @DisplayName("コード 1000（晴れ）→ sunny")
    void sunny_1000() {
        assertThat(WeatherConditionMapper.toIconKey(1000)).isEqualTo("sunny");
    }

    @Test
    @DisplayName("コード 1003（一部曇り）→ partly_cloudy")
    void partly_cloudy_1003() {
        assertThat(WeatherConditionMapper.toIconKey(1003)).isEqualTo("partly_cloudy");
    }

    @ParameterizedTest(name = "コード {0} → cloudy")
    @ValueSource(ints = {1006, 1009})
    @DisplayName("コード 1006 / 1009（曇り）→ cloudy")
    void cloudy_1006_1009(int code) {
        assertThat(WeatherConditionMapper.toIconKey(code)).isEqualTo("cloudy");
    }

    @ParameterizedTest(name = "コード {0} → heavy_rain（mist に上書きされた豪雨コード）")
    @ValueSource(ints = {1201, 1243, 1246})
    @DisplayName("コード 1201 / 1243 / 1246 → heavy_rain（mist に上書き確認）")
    void heavy_rain_overrides_mist(int code) {
        // 設計書 §13.5: 豪雨コードは mist の後に登録して上書き優先させる
        assertThat(WeatherConditionMapper.toIconKey(code)).isEqualTo("heavy_rain");
    }

    @Test
    @DisplayName("コード 1066（雪）→ snow")
    void snow_1066() {
        assertThat(WeatherConditionMapper.toIconKey(1066)).isEqualTo("snow");
    }

    @Test
    @DisplayName("コード 1087（雷雨）→ thunderstorm")
    void thunderstorm_1087() {
        assertThat(WeatherConditionMapper.toIconKey(1087)).isEqualTo("thunderstorm");
    }

    @Test
    @DisplayName("未知コード 9999 → cloudy（フォールバック確認）")
    void unknown_code_falls_back_to_cloudy() {
        // 設計書 §13.5: 未知コードは "cloudy" にフォールバック
        assertThat(WeatherConditionMapper.toIconKey(9999)).isEqualTo("cloudy");
    }
}
