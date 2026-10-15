package com.mannschaft.app.weather.controller;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.weather.config.WeatherRateLimiterConfig;
import com.mannschaft.app.weather.dto.DayForecastDto;
import com.mannschaft.app.weather.dto.WeatherForecastResponse;
import com.mannschaft.app.weather.dto.WeatherLocationRefreshResponse;
import com.mannschaft.app.weather.entity.UserWeatherLocationEntity;
import com.mannschaft.app.weather.exception.WeatherLocationDeriveException;
import com.mannschaft.app.weather.exception.WeatherProviderException;
import com.mannschaft.app.weather.repository.UserWeatherLocationRepository;
import com.mannschaft.app.weather.service.WeatherForecastResult;
import com.mannschaft.app.weather.service.WeatherForecastService;
import com.mannschaft.app.weather.service.WeatherLocationDeriver;
import com.mannschaft.app.weather.util.WeatherConditionMapper;
import io.github.bucket4j.Bucket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.security.SelfScopedEndpoint;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F02.10 天気ウィジェット — 天気予報 API Controller。
 *
 * <p>提供エンドポイント:
 * <ul>
 *   <li>{@code GET /api/v1/dashboard/weather} — 今日・明日・明後日の予報を返す（60 req/h/user）</li>
 *   <li>{@code POST /api/v1/users/me/weather-location/refresh} — 居住地点を再導出する（5 req/h/user）</li>
 * </ul>
 *
 * <p>設計書: docs/features/F02.10_weather_widget.md §5.1 / §5.3 / §6.3。</p>
 *
 * <p>セキュリティ:
 * <ul>
 *   <li>認証済みユーザーのデータのみ返却（{@code SecurityUtils.getCurrentUserId()} で自身の ID を使用）</li>
 *   <li>API キー・郵便番号・座標生値はログに出力しない</li>
 *   <li>Bucket4j によるユーザー単位レートリミットで DDoS 対策</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WeatherController {

    /** データソース名（固定）。 */
    private static final String DATA_SOURCE = "WeatherAPI.com";

    /** ISO 8601 UTC フォーマッタ（Z終端）。 */
    private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final WeatherForecastService weatherForecastService;
    private final WeatherLocationDeriver weatherLocationDeriver;
    private final UserWeatherLocationRepository userWeatherLocationRepository;
    private final UserRepository userRepository;
    private final WeatherRateLimiterConfig rateLimiterConfig;

    // ========================================
    // GET /api/v1/dashboard/weather
    // ========================================

    /**
     * 今日・明日・明後日の天気予報を返す。
     *
     * <p>処理フロー:
     * <ol>
     *   <li>レートリミット確認（60 req/h/user）</li>
     *   <li>地点キャッシュ取得（未登録なら同期で導出）</li>
     *   <li>WeatherForecastService で予報取得</li>
     *   <li>WeatherForecastResponse に変換して返却</li>
     * </ol>
     */
    @SelfScopedEndpoint("userId=SecurityUtils.getCurrentUserId() のみを対象にし、"
            + "UserWeatherLocationRepository#findByUserIdAndLabel が常に呼び出し元自身の地点情報のみを引く")
    @GetMapping("/dashboard/weather")
    public ResponseEntity<ApiResponse<WeatherForecastResponse>> getWeatherForecast() {
        Long userId = SecurityUtils.getCurrentUserId();

        // 1. レートリミットチェック（60 req/h/user）
        Bucket bucket = rateLimiterConfig.getForecastBucket(userId);
        if (!bucket.tryConsume(1)) {
            log.warn("天気予報 レートリミット超過: userId={}", userId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        // 2. 地点キャッシュ取得（未登録の場合は同期で導出）
        Optional<UserWeatherLocationEntity> locOpt =
                userWeatherLocationRepository.findByUserIdAndLabel(userId, "home");

        if (locOpt.isEmpty()) {
            // 初回: 同期で地点を導出・永続化（WeatherLocationDeriveException が発生することがある）
            log.debug("地点キャッシュ未登録のため同期導出: userId={}", userId);
            weatherLocationDeriver.deriveAndPersist(userId);
            locOpt = userWeatherLocationRepository.findByUserIdAndLabel(userId, "home");
        }

        // 導出後もなければ 404（郵便番号未登録ユーザーなど）
        if (locOpt.isEmpty()) {
            log.info("地点情報が取得できなかった: userId={}", userId);
            return ResponseEntity.notFound().build();
        }

        UserWeatherLocationEntity loc = locOpt.get();

        // 3. ユーザーの locale から表示言語を決定（未設定なら "ja"）
        String lang = resolveLanguage(userId);

        // 4. 予報取得（WeatherProviderException が発生することがある）
        WeatherForecastResult result = weatherForecastService.getForecast(
                loc.getCountryCode(),
                loc.getLatitudeRounded(),
                loc.getLongitudeRounded(),
                lang);

        // 5. レスポンス変換
        WeatherForecastResponse response = toForecastResponse(result, loc.getPlaceNameSnapshot());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========================================
    // POST /api/v1/users/me/weather-location/refresh
    // ========================================

    /**
     * ユーザーの居住地点を再導出して永続化する。
     *
     * <p>処理フロー:
     * <ol>
     *   <li>レートリミット確認（5 req/h/user）</li>
     *   <li>WeatherLocationDeriver で地点を同期導出・upsert</li>
     *   <li>WeatherLocationRefreshResponse を構築して返却</li>
     * </ol>
     */
    @SelfScopedEndpoint("userId=SecurityUtils.getCurrentUserId() のみを対象にし、"
            + "WeatherLocationDeriver#deriveAndPersist が常に呼び出し元自身の地点情報のみを upsert する")
    @PostMapping("/users/me/weather-location/refresh")
    public ResponseEntity<ApiResponse<WeatherLocationRefreshResponse>> refreshWeatherLocation() {
        Long userId = SecurityUtils.getCurrentUserId();

        // 1. レートリミットチェック（5 req/h/user）
        Bucket bucket = rateLimiterConfig.getRefreshBucket(userId);
        if (!bucket.tryConsume(1)) {
            log.warn("地点リフレッシュ レートリミット超過: userId={}", userId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        // 2. 地点を同期導出・upsert（WeatherLocationDeriveException が発生することがある）
        Optional<UserWeatherLocationEntity> savedOpt =
                weatherLocationDeriver.deriveAndPersist(userId);

        if (savedOpt.isEmpty()) {
            // ユーザー自体が見つからない（理論上、認証済みなら発生しないが念のため）
            log.warn("地点導出: ユーザーが見つからない（認証済みなのに不整合）: userId={}", userId);
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }

        // 3. レスポンス変換
        UserWeatherLocationEntity saved = savedOpt.get();
        WeatherLocationRefreshResponse response = new WeatherLocationRefreshResponse(
                saved.getPlaceNameSnapshot(),
                saved.getCountryCode(),
                saved.getDerivedAt().toInstant(ZoneOffset.UTC).toString()
        );

        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========================================
    // 例外ハンドラー
    // ========================================

    /**
     * 郵便番号導出失敗 → 422 Unprocessable Entity。
     *
     * <p>ErrorCode.name() をそのままクライアントに返すことで、
     * フロントエンドが「郵便番号未登録」「マスタ未ヒット」「国未対応」を区別できる。</p>
     */
    @ExceptionHandler(WeatherLocationDeriveException.class)
    public ResponseEntity<Map<String, String>> handleWeatherLocationDeriveException(
            WeatherLocationDeriveException e) {
        log.warn("地点導出失敗: errorCode={}", e.getErrorCode().name());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of(
                        "error_code", e.getErrorCode().name(),
                        "message", e.getMessage()
                ));
    }

    /**
     * WeatherAPI.com 呼び出し失敗 → 503 Service Unavailable。
     *
     * <p>stale キャッシュもない場合にサービス層がこの例外を伝播させる。
     * API キーは絶対にログに出さない。</p>
     */
    @ExceptionHandler(WeatherProviderException.class)
    public ResponseEntity<Map<String, String>> handleWeatherProviderException(
            WeatherProviderException e) {
        log.warn("天気プロバイダー呼び出し失敗: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error_code", "WEATHER_PROVIDER_UNAVAILABLE",
                        "message", "天気情報の取得に失敗しました。しばらく後に再試行してください。"
                ));
    }

    // ========================================
    // プライベートヘルパー
    // ========================================

    /**
     * WeatherForecastResult を WeatherForecastResponse に変換する。
     *
     * @param result      予報サービスの結果
     * @param placeName   ユーザーの居住地点名スナップショット（UI 表示用）
     */
    private WeatherForecastResponse toForecastResponse(WeatherForecastResult result, String placeName) {
        var data = result.data();

        List<DayForecastDto> forecasts = (data.getDays() == null ? List.<com.mannschaft.app.weather.client.WeatherForecastData.DayData>of()
                : data.getDays()).stream()
                .map(d -> new DayForecastDto(
                        d.getDate() != null ? d.getDate().toString() : null,
                        d.getConditionCode(),
                        d.getConditionText(),
                        WeatherConditionMapper.toIconKey(d.getConditionCode()),
                        d.getMaxTempC() != null ? d.getMaxTempC().doubleValue() : 0.0,
                        d.getMinTempC() != null ? d.getMinTempC().doubleValue() : 0.0,
                        d.getAvgHumidity(),
                        d.getChanceOfRain()))
                .toList();

        // fetchedAt を ISO 8601 UTC 文字列に変換
        Instant fetchedAt = data.getFetchedAt() != null ? data.getFetchedAt() : Instant.now();
        String fetchedAtStr = ISO_UTC.format(fetchedAt);

        return new WeatherForecastResponse(placeName, forecasts, DATA_SOURCE, fetchedAtStr, result.stale());
    }

    /**
     * ユーザーの locale フィールドから WeatherAPI.com 向け言語コードを解決する。
     *
     * <p>UserRepository で取得（IDOR 防止: 必ず自身の userId で引く）。
     * 取得できない・locale 未設定の場合は "ja" をデフォルト値とする。</p>
     */
    private String resolveLanguage(Long userId) {
        return userRepository.findById(userId)
                .map(UserEntity::getLocale)
                .filter(loc -> loc != null && !loc.isBlank())
                .map(loc -> loc.split("[-_]")[0].toLowerCase())
                .orElse("ja");
    }
}
