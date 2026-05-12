package com.mannschaft.app.weather.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.weather.config.WeatherApiProperties;
import com.mannschaft.app.weather.exception.WeatherProviderException;
import io.netty.channel.ChannelOption;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;
import reactor.netty.http.client.HttpClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * F02.10 天気ウィジェット — WeatherAPI.com の {@code /forecast.json} クライアント。
 *
 * <p>設計書: docs/features/F02.10_weather_widget.md §3 API 呼び出し仕様 / §7.3 / §8.2。</p>
 *
 * <ul>
 *   <li>クエリパラメータ: {@code q={lat},{lon}} / {@code days=2} / {@code aqi=no}
 *       / {@code alerts=no} / {@code lang} / {@code key}</li>
 *   <li>{@code lang} は {@code ja|en|zh|ko|es|de} 以外なら {@code en} にフォールバック</li>
 *   <li>5xx / タイムアウト → {@link Retryable} で最大 3 回（200ms→600ms→1.8s 相当）</li>
 *   <li>4xx → リトライせず {@link WeatherProviderException} を即時 throw</li>
 *   <li>ログ出力時の URL は {@code key=***} でマスク</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WeatherApiClient {

    /** WeatherAPI.com が公式にサポートする言語コード（設計書 §3）。 */
    private static final Set<String> SUPPORTED_LANGS = Set.of("ja", "en", "zh", "ko", "es", "de");

    /** {@code lang} が未対応のときのフォールバック先。 */
    private static final String FALLBACK_LANG = "en";

    /** 取得日数（今日 + 明日）。 */
    private static final int DAYS = 2;

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final WeatherApiProperties props;
    private final ObjectMapper objectMapper;

    private WebClient webClient;

    @PostConstruct
    void init() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getTimeoutMs());

        this.webClient = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();

        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            log.warn("WEATHER_API_KEY 未設定。WeatherAPI.com 呼び出しは 4xx 応答で失敗します。");
        } else {
            log.info("WeatherApiClient 初期化完了 (baseUrl={}, timeoutMs={})",
                    props.getBaseUrl(), props.getTimeoutMs());
        }
    }

    /**
     * テスト用 WebClient 差し替え（パッケージプライベート）。
     */
    void setWebClientForTest(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * 0.5 度丸めの緯度経度と表示言語を渡して、今日と明日の予報を取得する。
     *
     * @param latRounded 0.5 度丸めの緯度
     * @param lonRounded 0.5 度丸めの経度
     * @param lang       表示言語（未対応は {@code en} にフォールバック）
     * @return パース済み DTO。WeatherAPI.com が想定外の形を返した場合は {@link Optional#empty()}
     * @throws WeatherProviderException 4xx／リトライ尽き／JSON パース失敗
     */
    @Retryable(
            retryFor = {WebClientException.class},
            noRetryFor = {WebClientResponseException.BadRequest.class,
                    WebClientResponseException.Unauthorized.class,
                    WebClientResponseException.Forbidden.class,
                    WebClientResponseException.NotFound.class,
                    WebClientResponseException.MethodNotAllowed.class,
                    WebClientResponseException.NotAcceptable.class,
                    WebClientResponseException.UnsupportedMediaType.class,
                    WebClientResponseException.UnprocessableEntity.class,
                    WebClientResponseException.TooManyRequests.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 200, multiplier = 3))
    public Optional<WeatherForecastData> fetchForecast(
            BigDecimal latRounded, BigDecimal lonRounded, String lang) {

        String effectiveLang = normalizeLang(lang);
        String latLon = latRounded.toPlainString() + "," + lonRounded.toPlainString();

        // ログ用 URL（key=*** マスク）
        if (log.isDebugEnabled()) {
            log.debug("WeatherAPI.com 呼び出し: GET {}/forecast.json?q={}&days={}&aqi=no&alerts=no&lang={}&key=***",
                    props.getBaseUrl(), latLon, DAYS, effectiveLang);
        }

        try {
            String responseBody = webClient.get()
                    .uri(buildUri(latLon, effectiveLang))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::map4xx)
                    .bodyToMono(String.class)
                    .block();

            if (responseBody == null) {
                throw new WeatherProviderException("WeatherAPI.com レスポンスが空");
            }

            return Optional.ofNullable(parseResponse(responseBody));
        } catch (WeatherProviderException e) {
            throw e;
        } catch (WebClientResponseException e) {
            // is4xxClientError で onStatus 経由のはずだが、念のため握り直す
            if (e.getStatusCode().is4xxClientError()) {
                throw new WeatherProviderException(
                        "WeatherAPI.com 4xx 応答: " + e.getStatusCode().value(), e);
            }
            throw e; // 5xx はリトライ対象
        } catch (WebClientException e) {
            throw e; // タイムアウトもリトライ対象
        } catch (Exception e) {
            throw new WeatherProviderException("WeatherAPI.com レスポンス処理失敗", e);
        }
    }

    /**
     * Retry 上限到達後の {@link WebClientException} を {@link WeatherProviderException} でラップする。
     * Spring Retry の {@code @Recover} ハンドラ。
     */
    @org.springframework.retry.annotation.Recover
    Optional<WeatherForecastData> recover(WebClientException e,
                                          BigDecimal latRounded, BigDecimal lonRounded, String lang) {
        throw new WeatherProviderException("WeatherAPI.com 5xx/timeout (リトライ尽き)", e);
    }

    /** 言語フォールバック処理。 */
    String normalizeLang(String lang) {
        if (lang == null) return FALLBACK_LANG;
        String lower = lang.toLowerCase();
        return SUPPORTED_LANGS.contains(lower) ? lower : FALLBACK_LANG;
    }

    /** URI 構築（テストから検証可能なよう関数として切り出し）。 */
    Function<UriBuilder, java.net.URI> buildUri(String latLon, String lang) {
        return uri -> uri.path("/forecast.json")
                .queryParam("q", latLon)
                .queryParam("days", DAYS)
                .queryParam("aqi", "no")
                .queryParam("alerts", "no")
                .queryParam("lang", lang)
                .queryParam("key", props.getApiKey())
                .build();
    }

    /** 4xx ステータス → {@link WeatherProviderException}（リトライ対象外）。 */
    private reactor.core.publisher.Mono<? extends Throwable> map4xx(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new WeatherProviderException(
                        "WeatherAPI.com 4xx 応答: " + status + " / " + truncate(body, 200)));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * WeatherAPI.com JSON レスポンスを内部 DTO に変換する。
     */
    WeatherForecastData parseResponse(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode forecastDays = root.path("forecast").path("forecastday");
            if (!forecastDays.isArray() || forecastDays.size() < 2) {
                throw new WeatherProviderException(
                        "WeatherAPI.com forecast.forecastday に 2 日分のデータがない");
            }
            JsonNode todayNode = forecastDays.get(0);
            JsonNode tomorrowNode = forecastDays.get(1);

            return WeatherForecastData.builder()
                    .todayDate(parseDate(todayNode.path("date").asText()))
                    .tomorrowDate(parseDate(tomorrowNode.path("date").asText()))
                    .todayConditionCode(todayNode.path("day").path("condition").path("code").asInt())
                    .tomorrowConditionCode(tomorrowNode.path("day").path("condition").path("code").asInt())
                    .todayConditionText(todayNode.path("day").path("condition").path("text").asText())
                    .tomorrowConditionText(tomorrowNode.path("day").path("condition").path("text").asText())
                    .todayMaxTempC(readDecimal(todayNode.path("day").path("maxtemp_c")))
                    .todayMinTempC(readDecimal(todayNode.path("day").path("mintemp_c")))
                    .tomorrowMaxTempC(readDecimal(tomorrowNode.path("day").path("maxtemp_c")))
                    .tomorrowMinTempC(readDecimal(tomorrowNode.path("day").path("mintemp_c")))
                    .todayAvgHumidity(todayNode.path("day").path("avghumidity").asInt())
                    .tomorrowAvgHumidity(tomorrowNode.path("day").path("avghumidity").asInt())
                    .todayChanceOfRain(todayNode.path("day").path("daily_chance_of_rain").asInt())
                    .tomorrowChanceOfRain(tomorrowNode.path("day").path("daily_chance_of_rain").asInt())
                    .fetchedAt(Instant.now())
                    .build();
        } catch (WeatherProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new WeatherProviderException("WeatherAPI.com JSON パース失敗", e);
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            throw new WeatherProviderException("WeatherAPI.com レスポンスの date が空");
        }
        return LocalDate.parse(s, ISO_DATE);
    }

    private static BigDecimal readDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return BigDecimal.ZERO;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        return new BigDecimal(node.asText("0"));
    }
}
