package com.mannschaft.app.weather.exception;

/**
 * F02.10 天気ウィジェット — WeatherAPI.com 呼び出し系の例外。
 *
 * <p>4xx（API キー無効・無料枠超過・パラメータ不正）はリトライせず即時にこの例外を投げる。
 * 5xx／タイムアウトは {@link org.springframework.retry.annotation.Retryable} により
 * リトライ後に最終的に本例外でラップして上位へ伝播する。</p>
 *
 * <p>サービス層では本例外を捕捉し、Valkey に直近の stale データがあれば
 * stale フラグ付きで返却する。stale すらない場合はそのまま上位へ伝播し
 * {@code WEATHER_PROVIDER_UNAVAILABLE}（HTTP 503）として応答する。</p>
 */
public class WeatherProviderException extends RuntimeException {

    public WeatherProviderException(String message) {
        super(message);
    }

    public WeatherProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
