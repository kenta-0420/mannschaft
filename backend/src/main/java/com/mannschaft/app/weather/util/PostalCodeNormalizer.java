package com.mannschaft.app.weather.util;

/**
 * 郵便番号の国別正規化ユーティリティ。
 *
 * <p>F02.10 天気ウィジェットで、{@code postal_codes} マスタへの書き込み
 * （{@code GeonamesImportService}）とユーザー郵便番号の引き当て
 * （{@code WeatherLocationDeriver}）で同じ正規化を使うために、共通ロジックとして
 * 切り出した（2026-05-18 根治治療）。</p>
 *
 * <p>従来 {@code WeatherLocationDeriver} 内に private で持っていたロジックを移植し、
 * {@code GeonamesImportService.parseLine} でも呼び出す形に統一する。これによりマスタ側に
 * raw 形（ハイフン入り）と正規化形が混在する問題を解消する。</p>
 */
public final class PostalCodeNormalizer {

    private PostalCodeNormalizer() {
        // 静的ユーティリティ
    }

    /**
     * 国別フォーマットに正規化する。
     * <ul>
     *   <li>JP: 半角/全角ハイフン除去 + 7 桁ゼロパディング</li>
     *   <li>その他: トリム + 大文字化（英字を含む国向け）</li>
     * </ul>
     *
     * @param countryCode ISO 3166-1 alpha-2（null/blank 可、その場合は other 扱い）
     * @param postalCode  生の郵便番号（null 不可、blank 不可。呼び出し側でチェック必須）
     * @return 正規化済み郵便番号
     */
    public static String normalize(String countryCode, String postalCode) {
        String trimmed = postalCode.trim();
        if (countryCode != null && "JP".equalsIgnoreCase(countryCode)) {
            String digits = trimmed.replace("-", "").replace("‐", "").replace("ー", "");
            // 7 桁未満ならゼロパディング（マスタ側は 7 桁固定）
            if (digits.length() < 7) {
                digits = "0".repeat(7 - digits.length()) + digits;
            }
            return digits;
        }
        return trimmed.toUpperCase();
    }
}
