package com.mannschaft.app.auth.guardianship;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * F08.9 P3a 国別後見切替年齢ポリシーのレジストリ。
 *
 * <p>{@code country_code} に応じた {@link GuardianshipAgePolicy} を解決する（03_security §3.1）。
 * Spring が注入する全 {@link GuardianshipAgePolicy} Bean のうち、
 * {@link GuardianshipAgePolicy#supportedCountryCode()} を持つものを国コードで索引化し、
 * 該当なし・null・空文字の国コードは安全側フォールバック（{@link DefaultGuardianshipAgePolicy}）へ倒す。</p>
 *
 * <p>未対応国へのフォールバックは<b>症状を隠さずログに記録</b>する（障害対応の原則・根治治療）。
 * 「JP 以外は黙って既定」ではなく、どの国コードがフォールバックしたかを残し、
 * 将来の国別ポリシー追加の判断材料とする。</p>
 */
@Slf4j
@Component
public class GuardianshipAgePolicyRegistry {

    /** 国コード → 国別ポリシーの索引（大文字正規化キー）。 */
    private final Map<String, GuardianshipAgePolicy> policiesByCountry = new HashMap<>();

    /** 未対応国・国コード欠落時のフォールバックポリシー。 */
    private final GuardianshipAgePolicy fallbackPolicy;

    /**
     * 全ポリシー Bean とフォールバックポリシーを受け取り、国コード索引を構築する。
     *
     * @param policies        Spring が検出した全 {@link GuardianshipAgePolicy} 実装
     * @param fallbackPolicy  未対応国向けフォールバック（{@link DefaultGuardianshipAgePolicy}）
     */
    public GuardianshipAgePolicyRegistry(List<GuardianshipAgePolicy> policies,
                                         DefaultGuardianshipAgePolicy fallbackPolicy) {
        this.fallbackPolicy = fallbackPolicy;
        for (GuardianshipAgePolicy policy : policies) {
            String code = policy.supportedCountryCode();
            if (code != null && !code.isBlank()) {
                policiesByCountry.put(normalize(code), policy);
            }
        }
        log.info("後見切替年齢ポリシー登録完了: 対応国={} / フォールバック={}",
                policiesByCountry.keySet(), fallbackPolicy.getClass().getSimpleName());
    }

    /**
     * 国コードに対応する後見切替年齢ポリシーを返す。
     *
     * @param countryCode ISO 3166-1 alpha-2 国コード（null・空・未対応可）
     * @return 対応する {@link GuardianshipAgePolicy}（未対応・null・空はフォールバック）
     */
    public GuardianshipAgePolicy forCountry(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            // 国コード欠落は安全側フォールバック（03_security §3.1：country_code 欠落時はフォールバック適用）。
            log.warn("後見切替: country_code 欠落のためフォールバックポリシー（満13歳封印）を適用");
            return fallbackPolicy;
        }
        GuardianshipAgePolicy policy = policiesByCountry.get(normalize(countryCode));
        if (policy == null) {
            // 未対応国は安全側フォールバック＋記録（症状を隠さない）。
            // 正規化後の値（大文字化済み）でログすることで、元の入力ゆれを吸収して一貫したトレースを残す。
            log.warn("後見切替: 未対応の country_code={} のためフォールバックポリシー（満13歳封印）を適用", normalize(countryCode));
            return fallbackPolicy;
        }
        return policy;
    }

    /** 国コードを大文字に正規化する（索引キーの揺れ吸収）。 */
    private String normalize(String countryCode) {
        return countryCode.trim().toUpperCase(Locale.ROOT);
    }
}
