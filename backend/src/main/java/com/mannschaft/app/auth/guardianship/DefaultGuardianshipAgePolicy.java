package com.mannschaft.app.auth.guardianship;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * F08.9 P3a 未対応国向けフォールバック後見切替年齢ポリシー（安全側）。
 *
 * <p>判定基準（03_security §3.1）: 国別ポリシー未整備の {@code country_code} に対しては、
 * 安全側に倒して<b>満13歳の誕生日で封印</b>する。</p>
 * <ul>
 *   <li>満13歳の<b>誕生日前日まで</b> {@code switchAllowed=true}（未成年・{@code stageKey="minor"}）。</li>
 *   <li>満13歳の<b>誕生日当日以降</b> {@code switchAllowed=false}（自立段階・{@code stageKey="independent"}）。</li>
 * </ul>
 *
 * <p>境界は学齢（年度）ではなく実年齢（誕生日）で判定する（国共通の最も安全な単純規則）。
 * 学年制度のない国でも誤って切替を許し続けないよう、満13歳到達で確実に封じる。</p>
 *
 * <p>誕生日の評価タイムゾーンは注入された {@link Clock} のゾーンに従う（呼び出し側が
 * 適切なゾーンの Clock を渡す）。本番では UTC のシステム時計（共通 {@code ClockConfig} の
 * {@code utcClock} Bean）が注入される。</p>
 */
@Component
public class DefaultGuardianshipAgePolicy implements GuardianshipAgePolicy {

    /** この実年齢に達した誕生日で切替を封印する。 */
    private static final int SEAL_AGE = 13;

    /** 未成年段階（切替可）の i18n ラベルキー。 */
    private static final String STAGE_MINOR = "minor";

    /** 自立段階（封印）の i18n ラベルキー。 */
    private static final String STAGE_INDEPENDENT = "independent";

    @Override
    public AgeStageResolution resolve(LocalDate birthDate, Clock clock) {
        if (birthDate == null) {
            throw new IllegalArgumentException("birthDate must not be null for guardianship age resolution");
        }
        LocalDate today = LocalDate.now(clock);
        // 満13歳の誕生日。
        LocalDate sealDate = birthDate.plusYears(SEAL_AGE);

        // 誕生日前日まで切替可（誕生日当日に封印）。
        if (today.isBefore(sealDate)) {
            return new AgeStageResolution(true, STAGE_MINOR);
        }
        return new AgeStageResolution(false, STAGE_INDEPENDENT);
    }

    @Override
    public LocalDate sealDate(LocalDate birthDate, Clock clock) {
        if (birthDate == null) {
            throw new IllegalArgumentException("birthDate must not be null for guardianship seal date resolution");
        }
        // フォールバックは満13歳の誕生日で封印（resolve と同じ境界・clock 非依存）。
        return birthDate.plusYears(SEAL_AGE);
    }

    @Override
    public String supportedCountryCode() {
        // フォールバック実装は特定の国コードを持たない。
        return null;
    }
}
