package com.mannschaft.app.postal;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 国別郵便番号検証ポリシーの<strong>単一の真実源</strong>。
 *
 * <p>対応国のポリシー（{@link PostalCodePolicy}）を {@code Map} で保持し、生入力の
 * フォーマット検証（{@link #isValidFormat(String, String)}）と対応国判定
 * （{@link #isSupported(String)}）を提供する。</p>
 *
 * <p><b>拡張方針</b>: 対応国を 1 つ増やすには {@link #POLICIES} に 1 エントリ追加するだけでよい。
 * 検証ロジックは国コードをキーに自動的に効く。これにより「対応国の追加 = 1 行追加」を担保する。</p>
 *
 * <p><b>正規化との違い</b>: 本クラスは<strong>生入力</strong>を正規表現で評価する（正規化しない）。
 * 例えば JP の {@code "111"}（3 桁）は {@link com.mannschaft.app.weather.util.PostalCodeNormalizer}
 * ではゼロパディングされ得るが、本検証ではフォーマット不正として弾く。誤入力をユーザーに早期通知する
 * ためのバリデーションであり、マスタ引き当て用の正規化とは目的が異なる。</p>
 *
 * <p>設計書: F02.10 §391（郵便番号検証基盤）。</p>
 */
@Component
public class PostalCodePolicyRegistry {

    /**
     * 対応国のポリシー定義（単一の真実源）。
     *
     * <p>対応国を追加する場合はここに 1 エントリ足すだけでよい。キーは大文字の ISO 3166-1 alpha-2。</p>
     */
    private static final Map<String, PostalCodePolicy> POLICIES = Map.of(
            // JP: 7 桁（ハイフン任意）。例 123-4567 / 1234567。"111" 等の桁不足は弾く。
            "JP", new PostalCodePolicy("JP", "^\\d{3}-?\\d{4}$", "123-4567")
    );

    /**
     * 正規表現のコンパイル結果を国コードごとにキャッシュする。
     */
    private static final Map<String, Pattern> COMPILED = POLICIES.values().stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                    PostalCodePolicy::countryCode,
                    p -> Pattern.compile(p.pattern())));

    /**
     * 指定国コードが郵便番号検証の対応国かを返す。
     *
     * @param countryCode ISO 3166-1 alpha-2（大文字小文字は問わない。null/blank は false）
     * @return 対応国なら true
     */
    public boolean isSupported(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return false;
        }
        return POLICIES.containsKey(countryCode.toUpperCase());
    }

    /**
     * 指定国コードのポリシーを取得する。
     *
     * @param countryCode ISO 3166-1 alpha-2（大文字小文字は問わない）
     * @return 対応国ならポリシー、未対応なら空
     */
    public Optional<PostalCodePolicy> getPolicy(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(POLICIES.get(countryCode.toUpperCase()));
    }

    /**
     * 生入力の郵便番号が当該国のフォーマットに合致するかを返す。
     *
     * <p><strong>正規化はしない</strong>。生入力をそのまま正規表現で評価する。
     * 未対応国・null/blank 入力はすべて false。</p>
     *
     * @param countryCode    ISO 3166-1 alpha-2（大文字小文字は問わない）
     * @param rawPostalCode  生の郵便番号入力
     * @return フォーマットが合致すれば true
     */
    public boolean isValidFormat(String countryCode, String rawPostalCode) {
        if (countryCode == null || countryCode.isBlank()
                || rawPostalCode == null || rawPostalCode.isBlank()) {
            return false;
        }
        Pattern pattern = COMPILED.get(countryCode.toUpperCase());
        if (pattern == null) {
            return false;
        }
        return pattern.matcher(rawPostalCode).matches();
    }

    /**
     * 全対応国のポリシー一覧を返す（公開 API 用）。
     *
     * @return 対応国ポリシーの不変リスト
     */
    public List<PostalCodePolicy> all() {
        return List.copyOf(POLICIES.values());
    }
}
