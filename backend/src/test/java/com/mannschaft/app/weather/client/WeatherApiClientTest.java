package com.mannschaft.app.weather.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.weather.config.WeatherApiProperties;
import com.mannschaft.app.weather.exception.WeatherProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * F02.10 Phase 2 — {@link WeatherApiClient} の単体テスト。
 *
 * <p>WebClient を Mockito でモックし、(1) 正常系の DTO 変換、
 * (2) 4xx で {@link WeatherProviderException} 即時 throw、
 * (3) lang フォールバック、をカバーする。</p>
 *
 * <p>5xx リトライの動作確認は Spring Retry の AOP が必要なため、
 * {@code @Recover} と {@code recover()} メソッド単体の到達確認で代用する。</p>
 */
@DisplayName("WeatherApiClient 単体テスト")
class WeatherApiClientTest {

    private static final String SAMPLE_JSON = """
            {
              "location": {"name": "Tokyo"},
              "forecast": {
                "forecastday": [
                  {
                    "date": "2026-05-09",
                    "day": {
                      "maxtemp_c": 22.4,
                      "mintemp_c": 14.1,
                      "avghumidity": 58,
                      "daily_chance_of_rain": 10,
                      "condition": {"code": 1003, "text": "曇り時々晴れ"}
                    }
                  },
                  {
                    "date": "2026-05-10",
                    "day": {
                      "maxtemp_c": 18.0,
                      "mintemp_c": 13.5,
                      "avghumidity": 82,
                      "daily_chance_of_rain": 80,
                      "condition": {"code": 1063, "text": "雨"}
                    }
                  },
                  {
                    "date": "2026-05-11",
                    "day": {
                      "maxtemp_c": 24.0,
                      "mintemp_c": 15.0,
                      "avghumidity": 50,
                      "daily_chance_of_rain": 5,
                      "condition": {"code": 1000, "text": "晴れ"}
                    }
                  }
                ]
              }
            }
            """;

    private WeatherApiProperties props;
    private ObjectMapper objectMapper;
    private WeatherApiClient client;

    @BeforeEach
    void setUp() {
        props = new WeatherApiProperties();
        props.setBaseUrl("https://api.weatherapi.com/v1");
        props.setApiKey("test-key");
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        client = new WeatherApiClient(props, objectMapper);
    }

    @Test
    @DisplayName("正常系: 200 応答が WeatherForecastData に正しく変換される（3 日分）")
    void fetchForecast_parsesSuccessResponse() {
        client.setWebClientForTest(mockWebClient(SAMPLE_JSON, null));

        Optional<WeatherForecastData> result = client.fetchForecast(
                new BigDecimal("35.5"), new BigDecimal("139.5"), "ja");

        assertThat(result).isPresent();
        WeatherForecastData data = result.get();
        assertThat(data.getDays()).hasSize(3);
        // 今日
        assertThat(data.getDays().get(0).getDate().toString()).isEqualTo("2026-05-09");
        assertThat(data.getDays().get(0).getConditionCode()).isEqualTo(1003);
        assertThat(data.getDays().get(0).getConditionText()).isEqualTo("曇り時々晴れ");
        assertThat(data.getDays().get(0).getMaxTempC()).isEqualByComparingTo("22.4");
        assertThat(data.getDays().get(0).getMinTempC()).isEqualByComparingTo("14.1");
        // 明日
        assertThat(data.getDays().get(1).getDate().toString()).isEqualTo("2026-05-10");
        assertThat(data.getDays().get(1).getAvgHumidity()).isEqualTo(82);
        assertThat(data.getDays().get(1).getChanceOfRain()).isEqualTo(80);
        // 明後日
        assertThat(data.getDays().get(2).getDate().toString()).isEqualTo("2026-05-11");
        assertThat(data.getDays().get(2).getConditionCode()).isEqualTo(1000);
        assertThat(data.getDays().get(2).getConditionText()).isEqualTo("晴れ");
        assertThat(data.getDays().get(2).getMaxTempC()).isEqualByComparingTo("24.0");
        assertThat(data.getFetchedAt()).isNotNull();
    }

    @Test
    @DisplayName("4xx (401 Unauthorized) → リトライせず WeatherProviderException 即時")
    void fetchForecast_throwsImmediatelyOn4xx() {
        WebClientResponseException ex = WebClientResponseException.create(
                HttpStatus.UNAUTHORIZED.value(), "Unauthorized",
                null, "{\"error\":{\"code\":2006,\"message\":\"API key invalid\"}}".getBytes(),
                null);
        client.setWebClientForTest(mockWebClient(null, ex));

        assertThatThrownBy(() -> client.fetchForecast(
                new BigDecimal("35.5"), new BigDecimal("139.5"), "ja"))
                .isInstanceOf(WeatherProviderException.class)
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("lang フォールバック: 未対応言語 fr → en に置換される")
    void normalizeLang_fallsBackUnsupportedToEn() {
        assertThat(client.normalizeLang("fr")).isEqualTo("en");
        assertThat(client.normalizeLang("FR")).isEqualTo("en");
        assertThat(client.normalizeLang(null)).isEqualTo("en");
        assertThat(client.normalizeLang("ja")).isEqualTo("ja");
        assertThat(client.normalizeLang("EN")).isEqualTo("en");
        assertThat(client.normalizeLang("zh")).isEqualTo("zh");
        assertThat(client.normalizeLang("de")).isEqualTo("de");
    }

    @Test
    @DisplayName("@Recover: 5xx リトライ尽き → WeatherProviderException でラップ")
    void recover_wrapsWebClientException() {
        WebClientResponseException ex = WebClientResponseException.create(
                HttpStatus.SERVICE_UNAVAILABLE.value(), "Service Unavailable",
                null, null, null);
        assertThatThrownBy(() -> client.recover(ex,
                new BigDecimal("35.5"), new BigDecimal("139.5"), "ja"))
                .isInstanceOf(WeatherProviderException.class)
                .hasMessageContaining("リトライ尽き");
    }

    @Test
    @DisplayName("forecastday が空 → WeatherProviderException")
    void parseResponse_throwsWhenForecastdayEmpty() {
        String emptyJson = """
                {
                  "forecast": {
                    "forecastday": []
                  }
                }
                """;
        assertThatThrownBy(() -> client.parseResponse(emptyJson))
                .isInstanceOf(WeatherProviderException.class);
    }

    @Test
    @DisplayName("forecastday が 3 件未満 (例: 2 件) でも実数分だけ返却される（堅牢性）")
    void parseResponse_returnsAvailableDaysOnlyWhenLessThan3() {
        String twoDayJson = """
                {
                  "forecast": {
                    "forecastday": [
                      {"date": "2026-05-09", "day": {"maxtemp_c": 22.4, "mintemp_c": 14.1, "avghumidity": 58, "daily_chance_of_rain": 10, "condition": {"code": 1003, "text": "曇り時々晴れ"}}},
                      {"date": "2026-05-10", "day": {"maxtemp_c": 18.0, "mintemp_c": 13.5, "avghumidity": 82, "daily_chance_of_rain": 80, "condition": {"code": 1063, "text": "雨"}}}
                    ]
                  }
                }
                """;
        WeatherForecastData data = client.parseResponse(twoDayJson);
        assertThat(data.getDays()).hasSize(2);
    }

    /**
     * WebClient の {@code get().uri(...).retrieve().onStatus(...).bodyToMono(String.class).block()}
     * をモックする。
     *
     * @param responseBody 成功時に返すボディ。{@code null} の場合は exception を投げる
     * @param exception    レスポンス取得時に投げる例外（{@code null} なら responseBody を返却）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private WebClient mockWebClient(String responseBody, Throwable exception) {
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestHeadersUriSpec uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        given(webClient.get()).willReturn(uriSpec);
        // uri(Function<UriBuilder, URI>) のオーバーロード
        given(uriSpec.uri(any(java.util.function.Function.class)))
                .willReturn((WebClient.RequestHeadersSpec) headersSpec);
        given(headersSpec.retrieve()).willReturn(responseSpec);
        // onStatus は本実装をそのまま通過させる
        given(responseSpec.onStatus(any(), any())).willReturn(responseSpec);

        if (exception != null) {
            given(responseSpec.bodyToMono(String.class)).willReturn(Mono.error(exception));
        } else {
            given(responseSpec.bodyToMono(String.class)).willReturn(Mono.just(responseBody));
        }
        return webClient;
    }
}
