package com.mannschaft.app.postal;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * ユーザーの実効国コードを解決する共有コンポーネント。
 *
 * <p>明示的な国コードがあればそれを大文字で採用し、なければ locale のプレフィックス
 * （言語コード）から国コードを推定する。weather ドメインの地点導出
 * （{@link com.mannschaft.app.weather.service.WeatherLocationDeriver}）と
 * 郵便番号検証（auth ドメイン）の両方が同じ locale→国 マップを使うため、
 * <strong>auth でも weather でもない中立な {@code postal} パッケージ</strong>に集約した。
 * これにより locale→国 マップの二重持ち（ドリフト源）を防ぐ。</p>
 *
 * <p>設計書: F02.10 §391（郵便番号検証基盤）。</p>
 */
@Component
public class CountryResolver {

    /**
     * 実効国コードを解決する。
     *
     * <ul>
     *   <li>{@code countryCode} が非 blank ならそれを大文字化して返す</li>
     *   <li>NULL/blank なら {@code locale} のプレフィックス（ja→JP, en→US, zh→CN, ko→KR,
     *       es→ES, de→DE）から推定する</li>
     *   <li>いずれでも解決できなければ {@link Optional#empty()}</li>
     * </ul>
     *
     * @param countryCode 明示的な国コード（null 可）
     * @param locale      ロケール文字列（例: {@code "ja"} / {@code "ja-JP"}。null 可）
     * @return 解決された国コード（大文字）。解決不能なら空
     */
    public Optional<String> resolve(String countryCode, String locale) {
        if (countryCode != null && !countryCode.isBlank()) {
            return Optional.of(countryCode.toUpperCase());
        }
        if (locale == null || locale.isBlank()) {
            return Optional.empty();
        }
        String langCode = locale.split("[-_]")[0].toLowerCase();
        return switch (langCode) {
            case "ja" -> Optional.of("JP");
            case "en" -> Optional.of("US");
            case "zh" -> Optional.of("CN");
            case "ko" -> Optional.of("KR");
            case "es" -> Optional.of("ES");
            case "de" -> Optional.of("DE");
            default -> Optional.empty();
        };
    }
}
